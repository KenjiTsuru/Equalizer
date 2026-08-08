package com.example.autoeq;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import android.media.audiofx.Equalizer;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.List;

public class EqualizerEditorFragment extends Fragment {

    private Equalizer systemEq;
    private SelectedEqualizer currentEq;
    private NavigationView navView;
    private short minMb, maxMb;
    private LinearLayout bandsContainer;

    // Firebase Data Handler reference replaces local indexing pools
    private EqualizerDataHandler dataHandler;
    private List<SelectedEqualizer> presets = new ArrayList<>();

    private View emptyStateText;
    private View eqUiContainer;
    private MaterialToolbar toolbar;
    private short numBands;
    private int span;

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
            int bandCount = systemEq.getNumberOfBands();

            // levels can come back null or shorter than bandCount - a preset saved
            // before this fix, a Firebase read that hasn't fully resolved yet, or a
            // preset created on a device with a different band count. Always fully
            // resync every band instead of skipping, defaulting anything missing to
            // 0 mB, and write the result back onto currentEq so the in-memory model
            // is never null/short going forward.
            List<Integer> safeLevels = new ArrayList<>(bandCount);
            for (int i = 0; i < bandCount; i++) {
                int level = (levels != null && i < levels.size() && levels.get(i) != null) ? levels.get(i) : 0;
                safeLevels.add(level);
                systemEq.setBandLevel((short) i, (short) level);
            }
            currentEq.setBandLevels(safeLevels);

            // Redraw layout tracks to fit the loaded properties
            buildBandUiFromSystemEqualizer();
        }
    }

    private void updateCurrentEqDisplay() {
        if (toolbar != null) {
            toolbar.setTitle(currentEq != null ? currentEq.getDisplayName() : "");
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
                    int numBands = (systemEq != null) ? systemEq.getNumberOfBands() : 5;

                    // Build modern dynamic generic collection arrays explicitly
                    List<Integer> bandIds = new ArrayList<>();
                    List<Integer> initialLevels = new ArrayList<>();

                    for (short i = 0; i < numBands; i++) {
                        bandIds.add((int) i);
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
            systemEq = new Equalizer(0, audioSessionId);
            systemEq.setEnabled(true);

            short[] range = systemEq.getBandLevelRange();
            minMb = range[0];
            maxMb = range[1];
            numBands = systemEq.getNumberOfBands();
            span = maxMb - minMb;

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


        for (short band = 0; band < numBands; band++) {
            final short finalBand = band;

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

            sb.setMax(span);
            short currentMb = systemEq.getBandLevel(finalBand);
            sb.setProgress(currentMb - minMb);

            if (tooltip != null) {
                tooltip.setText(currentMb + " mB");
            }

            if (label != null) {
                int centerFreqHz = systemEq.getCenterFreq(finalBand) / 1000;
                label.setText(centerFreqHz >= 1000 ? (centerFreqHz / 1000) + " kHz" : centerFreqHz + " Hz");
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
                    if (systemEq != null) {
                        int targetMb = minMb + progress;
                        systemEq.setBandLevel(finalBand, (short) targetMb);

                        if (currentEq != null && currentEq.getBandLevels() != null
                                && finalBand < currentEq.getBandLevels().size()) {
                            currentEq.getBandLevels().set(finalBand, targetMb);
                        }
                    }


                    if (tooltip != null) {
                        int targetDb = (minMb + progress);
                        tooltip.setText(targetDb + " mB");
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
     * always holds exactly numBands valid short values, so this can never hand
     * Firebase a null or short-length list.
     */
    private void persistCurrentBandLevels() {
        if (currentEq == null || dataHandler == null || systemEq == null) return;

        List<Integer> freshLevels = new ArrayList<>(numBands);
        for (short i = 0; i < numBands; i++) {
            freshLevels.add((int) systemEq.getBandLevel(i));
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