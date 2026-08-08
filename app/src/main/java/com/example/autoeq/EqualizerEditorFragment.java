package com.example.autoeq;

import android.app.AlertDialog;
import android.media.audiofx.DynamicsProcessing;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class EqualizerEditorFragment extends Fragment {

    // EQ shape: 12 bands, log-spaced 30 Hz-8000 Hz, +/-12 dB range. Stored and
    // passed around as tenths-of-a-dB integers (e.g. 35 = 3.5 dB) so
    // SelectedEqualizer's List<Integer> and the SeekBar's integer progress
    // don't need to change shape just because DynamicsProcessing's native
    // unit is a float dB rather than the old Equalizer's millibel short.
    private static final int NUM_BANDS = 12;
    private static final float[] BAND_FREQUENCIES_HZ = {
            30f, 50f, 83f, 138f, 229f, 380f, 632f, 1050f, 1744f, 2900f, 4800f, 8000f
    };
    private static final float MIN_GAIN_DB = -12f;
    private static final float MAX_GAIN_DB = 12f;
    private static final int MIN_LEVEL = Math.round(MIN_GAIN_DB * 10);
    private static final int MAX_LEVEL = Math.round(MAX_GAIN_DB * 10);
    private static final int SPAN = MAX_LEVEL - MIN_LEVEL;

    private DynamicsProcessing systemEq;
    private SelectedEqualizer currentEq;
    private NavigationView navView;
    private LinearLayout bandsContainer;

    // Firebase Data Handler reference replaces local indexing pools
    private EqualizerDataHandler dataHandler;
    private List<SelectedEqualizer> presets = new ArrayList<>();

    private View emptyStateText;
    private View eqUiContainer;
    private MaterialToolbar toolbar;
    private TextView presetNameText;

    // Debounced save: onProgressChanged is the confirmed-firing callback, so
    // it's what actually schedules the Firebase write. Every progress change
    // resets this timer; the write only goes out once movement pauses.
    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable pendingBandLevelSave = this::persistCurrentBandLevels;
    private static final long BAND_LEVEL_SAVE_DEBOUNCE_MS = 400;

    public EqualizerEditorFragment() {}

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.equalizer_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        DrawerLayout drawerLayout = view.findViewById(R.id.eq_drawer);
        toolbar = view.findViewById(R.id.eq_toolbar);
        navView = view.findViewById(R.id.eq_nav_view);
        emptyStateText = view.findViewById(R.id.eq_empty_state_text);
        eqUiContainer = view.findViewById(R.id.equalizer_ui_container);
        bandsContainer = view.findViewById(R.id.eq_bands_row);
        presetNameText = view.findViewById(R.id.eq_preset_name);

        toolbar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size);
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        View headerView = navView.getHeaderView(0);
        View btnCreate = headerView.findViewById(R.id.btn_create_eq);
        EditText searchBar = headerView.findViewById(R.id.drawer_search_bar);

        if (searchBar != null) {
            searchBar.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    filterDrawerMenu(s.toString());
                }
                @Override
                public void afterTextChanged(android.text.Editable s) {}
            });
        }

        if (btnCreate != null) {
            btnCreate.setOnClickListener(v -> {
                drawerLayout.closeDrawer(GravityCompat.START);
                showCreateEqualizerDialog();
            });
        }

        dataHandler = new EqualizerDataHandler();

        // Handle navigation items by dynamic string matching instead of hardcoded menu IDs
        navView.setNavigationItemSelectedListener(item -> {
            String selectedName = item.getTitle().toString();
            for (SelectedEqualizer eq : presets) {
                if (eq.getDisplayName().equals(selectedName)) {
                    applySelectedPreset(eq);
                    drawerLayout.closeDrawer(GravityCompat.START);
                    return true;
                }
            }
            drawerLayout.closeDrawer(GravityCompat.START);
            return false;
        });

        initSystemEqualizer(0);

        // Global on/off for the system equalizer effect - not tied to any preset.
        SwitchCompat powerSwitch = view.findViewById(R.id.eq_power_switch);
        if (powerSwitch != null) {
            powerSwitch.setEnabled(systemEq != null);
            powerSwitch.setChecked(systemEq != null && systemEq.getEnabled());
            powerSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (systemEq != null) {
                    systemEq.setEnabled(isChecked);
                }
            });
        }

        View settingsButton = view.findViewById(R.id.eq_settings_button);
        if (settingsButton != null) {
            settingsButton.setOnClickListener(v ->
                    Toast.makeText(requireContext(), "Settings coming soon", Toast.LENGTH_SHORT).show());
        }

        // Start listening directly to Firebase node updates
        dataHandler.listenToPresets(new EqualizerDataHandler.PresetsListener() {
            @Override
            public void onPresetsLoaded(List<SelectedEqualizer> updatedPresets) {
                if (!isAdded()) return;

                String currentId = currentEq != null ? currentEq.getId() : null;
                int oldIndex = -1;

                // Find the index of the current item in the old list (if it exists)
                if (currentId != null) {
                    for (int i = 0; i < presets.size(); i++) {
                        if (presets.get(i).getId().equals(currentId)) {
                            oldIndex = i;
                            break;
                        }
                    }
                }

                presets = updatedPresets;

                String currentQuery = searchBar.getText().toString();
                if (currentQuery.isEmpty()) {
                    updateDrawerMenu();
                } else {
                    filterDrawerMenu(currentQuery);
                }

                if (!presets.isEmpty()) {
                    showEqualizerUi();

                    boolean stillExists = false;

                    for(SelectedEqualizer eq : presets) {
                        if (currentId != null && currentId.equals(eq.getId())){
                            stillExists = true;
                            break;
                        }
                    }


                    if (!stillExists && currentId != null) {
                        int newIndex;
                        if (oldIndex < presets.size()) {
                            // Switch to the next one in line (which now occupies the old index)
                            newIndex = oldIndex;
                        } else {
                            // It was the last one in the list, switch to the new last one (above it)
                            newIndex = presets.size() - 1;
                        }

                        if (newIndex >= 0) {
                            applySelectedPreset(presets.get(newIndex));
                        }
                    } else if (currentEq == null) {
                        // Standard fallback for initial load
                        applySelectedPreset(presets.get(0));
                    }

                } else {
                    currentEq = null;
                    updateCurrentEqDisplay();
                    if (emptyStateText != null) emptyStateText.setVisibility(View.VISIBLE);
                    if (eqUiContainer != null) eqUiContainer.setVisibility(View.GONE);
                }
            }

            @Override
            public void onError(Exception e) {
                if (!isAdded()) return;
                Toast.makeText(getContext(), "Database Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void filterDrawerMenu(String query) {android.view.Menu menu = navView.getMenu();
        menu.clear(); // Clear current items

        int groupId = 2;
        int dynamicId = 2000;

        for (SelectedEqualizer eq : presets) {
            // Only add items that match the search query (case-insensitive)
            if (eq.getDisplayName().toLowerCase().contains(query.toLowerCase())) {
                android.view.MenuItem item = menu.add(groupId, dynamicId++, android.view.Menu.NONE, eq.getDisplayName())
                        .setIcon(android.R.drawable.ic_media_next);

                item.setActionView(R.layout.menu_delete_action);
                View deleteBtn = item.getActionView().findViewById(R.id.btn_delete_preset);
                deleteBtn.setOnClickListener(v -> showDeleteConfirmationDialog(eq));
            }
        }
    }

    private void applySelectedPreset(SelectedEqualizer eq) {
        currentEq = eq;
        updateCurrentEqDisplay();

        if (systemEq != null && currentEq != null) {
            List<Integer> levels = currentEq.getBandLevels();

            // levels can come back null or shorter than NUM_BANDS - a preset saved
            // before this fix, a Firebase read that hasn't fully resolved yet, or
            // (now) an old 5-band preset from before the 12-band migration. Always
            // fully resync every band instead of skipping, defaulting anything
            // missing to 0 dB, and write the result back onto currentEq so the
            // in-memory model is never null/short going forward.
            List<Integer> safeLevels = new ArrayList<>(NUM_BANDS);
            for (int i = 0; i < NUM_BANDS; i++) {
                int level = (levels != null && i < levels.size() && levels.get(i) != null) ? levels.get(i) : 0;
                safeLevels.add(level);
                systemEq.setPreEqBandAllChannelsTo(i,
                        new DynamicsProcessing.EqBand(true, BAND_FREQUENCIES_HZ[i], levelToGainDb(level)));
            }
            currentEq.setBandLevels(safeLevels);

            // Redraw layout tracks to fit the loaded properties
            buildBandUiFromSystemEqualizer();
        }
    }

    private void updateCurrentEqDisplay() {
        if (presetNameText != null) {
            presetNameText.setText(currentEq != null ? currentEq.getDisplayName() : "");
        }
    }

    private void showEqualizerUi() {
        if (emptyStateText != null) emptyStateText.setVisibility(View.GONE);
        if (eqUiContainer != null) eqUiContainer.setVisibility(View.VISIBLE);
        if (systemEq != null && bandsContainer.getChildCount() == 0) {
            buildBandUiFromSystemEqualizer();
        }
    }

    private void showCreateEqualizerDialog() {
        LinearLayout layout = new LinearLayout(requireContext());
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText songNameInput = new EditText(requireContext());
        songNameInput.setHint("Song Name");
        layout.addView(songNameInput);

        final EditText artistNameInput = new EditText(requireContext());
        artistNameInput.setHint("Artist Name");
        layout.addView(artistNameInput);

        final Spinner typeSpinner = new Spinner(requireContext());
        String[] types = {"Song and Artist", "Genre"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(), android.R.layout.simple_spinner_item, types);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        typeSpinner.setAdapter(adapter);
        layout.addView(typeSpinner);

        typeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position == 0) {
                    songNameInput.setHint("Song Name");
                    artistNameInput.setVisibility(View.VISIBLE);
                } else {
                    songNameInput.setHint("Genre Name");
                    artistNameInput.setVisibility(View.GONE);
                }
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });

        new AlertDialog.Builder(requireContext())
                .setTitle("Create your equalizer")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = songNameInput.getText().toString().trim();
                    String artist = artistNameInput.getText().toString().trim();
                    if (name.isEmpty()) name = "Untitled";

                    int type = typeSpinner.getSelectedItemPosition();

                    // Build modern dynamic generic collection arrays explicitly
                    List<Integer> bandIds = new ArrayList<>();
                    List<Integer> initialLevels = new ArrayList<>();

                    for (int i = 0; i < NUM_BANDS; i++) {
                        bandIds.add(i);
                        initialLevels.add(0);
                    }

                    SelectedEqualizer eq = new SelectedEqualizer(name, artist, type, bandIds, initialLevels);

                    // Initialize data handler on the fly if it hasn't been instantiated yet
                    if (dataHandler == null) {
                        dataHandler = new EqualizerDataHandler();
                    }

                    dataHandler.saveEqualizer(eq, new EqualizerDataHandler.OperationCallback() {
                        @Override
                        public void onSuccess() {
                            if (isAdded() && getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    applySelectedPreset(eq);
                                    Toast.makeText(requireContext(), "Saved to Cloud: " + eq.getDisplayName(), Toast.LENGTH_SHORT).show();
                                });
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {
                            if (isAdded() && getActivity() != null) {
                                getActivity().runOnUiThread(() -> {
                                    Toast.makeText(requireContext(), "Cloud Save Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                                });
                            }
                        }
                    });
                })
                .show();
    }

    private void updateDrawerMenu() {
        android.view.Menu menu = navView.getMenu();
        menu.clear(); // Safe clean clearing operation execution path

        int groupId = 2;
        int dynamicId = 2000;

        for (SelectedEqualizer eq : presets) {
            android.view.MenuItem item = menu.add(groupId, dynamicId++, android.view.Menu.NONE, eq.getDisplayName())
                    .setIcon(android.R.drawable.ic_media_next);

            item.setActionView(R.layout.menu_delete_action);

            View actionView = item.getActionView();
            View deleteBtn = actionView.findViewById(R.id.btn_delete_preset);

            deleteBtn.setOnClickListener(v -> {
                DrawerLayout drawer = getView().findViewById(R.id.eq_drawer);
                if(drawer != null) drawer.closeDrawer(GravityCompat.START);

                showDeleteConfirmationDialog(eq);
            });
        }
    }

    private void showDeleteConfirmationDialog(SelectedEqualizer eq) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete Preset")
                .setMessage("Are you sure you want to delete '" + eq.getDisplayName() + "'?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    deletePreset(eq);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void deletePreset(SelectedEqualizer eq) {
        if (dataHandler != null) {
            dataHandler.deleteEqualizer(eq, new EqualizerDataHandler.OperationCallback() {
                @Override
                public void onSuccess() {
                    // No need to manually refresh; listenToPresets will trigger automatically
                    Toast.makeText(getContext(), "Deleted successfully", Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onFailure(Exception e) {
                    Toast.makeText(getContext(), "Delete failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            });
        }
    }

    private void initSystemEqualizer(int audioSessionId) {
        try {
            DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    /* channelCount= */ 2,
                    /* preEqInUse= */ true,
                    /* preEqBandCount= */ NUM_BANDS,
                    /* mbcInUse= */ false,
                    /* mbcBandCount= */ 0,
                    /* postEqInUse= */ false,
                    /* postEqBandCount= */ 0,
                    /* limiterInUse= */ false)
                    .build();

            systemEq = new DynamicsProcessing(/* priority= */ 0, audioSessionId, config);
            systemEq.setEnabled(true);

            // The Builder above seeds every band with its own default frequency
            // spacing; overwrite each one now with our 30 Hz-8000 Hz layout so
            // the initial engine state goes through the exact same call every
            // later live update uses.
            for (int b = 0; b < NUM_BANDS; b++) {
                systemEq.setPreEqBandAllChannelsTo(b,
                        new DynamicsProcessing.EqBand(true, BAND_FREQUENCIES_HZ[b], 0f));
            }

        } catch (Throwable t) {
            systemEq = null;
            Toast.makeText(requireContext(), "Equalizer not supported: " + t.getClass().getSimpleName(), Toast.LENGTH_LONG).show();
        }
    }

    private void buildBandUiFromSystemEqualizer() {
        bandsContainer.removeAllViews();
        if (systemEq == null) return;

        bandsContainer.setClipChildren(false);
        bandsContainer.setClipToPadding(false);


        for (int band = 0; band < NUM_BANDS; band++) {
            final int finalBand = band;

            View bandView = LayoutInflater.from(requireContext()).inflate(R.layout.equalizer_band_item, bandsContainer, false);


            if (bandView instanceof ViewGroup) {
                ((ViewGroup) bandView).setClipChildren(false);
                ((ViewGroup) bandView).setClipToPadding(false);
            }

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
            bandView.setLayoutParams(params);

            VerticalSeekBar sb = bandView.findViewById(R.id.eq_band_seekbar);
            TextView tooltip = bandView.findViewById(R.id.text_bubble);
            TextView label = bandView.findViewById(R.id.eq_band_label);

            sb.setMax(SPAN);
            int currentLevel = gainDbToLevel(systemEq.getPreEqBandByChannelIndex(0, finalBand).getGain());
            sb.setProgress(currentLevel - MIN_LEVEL);

            if (tooltip != null) {
                tooltip.setText(formatLevelAsDb(currentLevel));
            }

            if (label != null) {
                label.setText(formatFrequencyLabel(BAND_FREQUENCIES_HZ[finalBand]));
            }



            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    // Updates the live audio engine + in-memory model + tooltip
                    // immediately. The Firebase write is debounced below: every
                    // call here resets a short timer, so the write only fires
                    // once movement pauses. This is the primary save path -
                    // onStopTrackingTouch isn't reliably called by every seekbar
                    // implementation, so saving doesn't depend on it firing.
                    int targetLevel = MIN_LEVEL + progress;

                    if (systemEq != null) {
                        systemEq.setPreEqBandAllChannelsTo(finalBand,
                                new DynamicsProcessing.EqBand(true, BAND_FREQUENCIES_HZ[finalBand], levelToGainDb(targetLevel)));

                        if (currentEq != null && currentEq.getBandLevels() != null
                                && finalBand < currentEq.getBandLevels().size()) {
                            currentEq.getBandLevels().set(finalBand, targetLevel);
                        }
                    }


                    if (tooltip != null) {
                        tooltip.setText(formatLevelAsDb(targetLevel));
                        tooltip.setVisibility(View.VISIBLE);
                    }

                    if (currentEq != null && dataHandler != null) {
                        saveHandler.removeCallbacks(pendingBandLevelSave);
                        saveHandler.postDelayed(pendingBandLevelSave, BAND_LEVEL_SAVE_DEBOUNCE_MS);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    // If this DOES fire, save immediately instead of waiting out
                    // the debounce timer scheduled above.
                    saveHandler.removeCallbacks(pendingBandLevelSave);
                    persistCurrentBandLevels();
                }
            });

            bandsContainer.addView(bandView);
        }
    }

    /**
     * Saves the full set of band levels for the current preset, read directly
     * from systemEq (the live audio engine) rather than trusting the in-memory
     * currentEq.bandLevels list to have stayed perfectly in sync. systemEq
     * always holds exactly NUM_BANDS valid bands, so this can never hand
     * Firebase a null or short-length list.
     */
    private void persistCurrentBandLevels() {
        if (currentEq == null || dataHandler == null || systemEq == null) return;

        List<Integer> freshLevels = new ArrayList<>(NUM_BANDS);
        for (int i = 0; i < NUM_BANDS; i++) {
            float gainDb = systemEq.getPreEqBandByChannelIndex(0, i).getGain();
            freshLevels.add(gainDbToLevel(gainDb));
        }
        currentEq.setBandLevels(freshLevels);

        String presetId = currentEq.getId();
        dataHandler.updateBandLevels(presetId, freshLevels, new EqualizerDataHandler.OperationCallback() {
            @Override
            public void onSuccess() {
                Log.d("EQ_SAVE", "Band levels saved for " + presetId);
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("EQ_SAVE", "Failed to save band levels", e);
            }
        });
    }

    private static int gainDbToLevel(float gainDb) {
        return Math.round(gainDb * 10f);
    }

    private static float levelToGainDb(int level) {
        return level / 10f;
    }

    private static String formatLevelAsDb(int level) {
        return String.format(Locale.US, "%.1f dB", levelToGainDb(level));
    }

    private static String formatFrequencyLabel(float freqHz) {
        if (freqHz >= 1000f) {
            return String.format(Locale.US, "%.1f kHz", freqHz / 1000f);
        }
        return Math.round(freqHz) + " Hz";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        saveHandler.removeCallbacks(pendingBandLevelSave);
        if (dataHandler != null) {
            dataHandler.stopListening();
        }
        if (systemEq != null) {
            systemEq.release();
            systemEq = null;
        }
    }
}