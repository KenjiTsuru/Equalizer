package com.example.autoeq;

import android.app.AlertDialog;
import android.os.Bundle;
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
                presets = updatedPresets;

                String currentQuery = searchBar.getText().toString();
                if (currentQuery.isEmpty()) {
                    updateDrawerMenu();
                } else {
                    filterDrawerMenu(currentQuery);
                }

                if (!presets.isEmpty()) {
                    showEqualizerUi();
                    if (currentEq == null) {
                        applySelectedPreset(presets.get(0)); // Standard fallback selection
                    }
                } else {
                    if (emptyStateText != null) emptyStateText.setVisibility(View.VISIBLE);
                    if (eqUiContainer != null) eqUiContainer.setVisibility(View.GONE);
                }
            }

            @Override
            public void onError(Exception e) {
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
            if (levels != null) {
                for (short i = 0; i < levels.size() && i < systemEq.getNumberOfBands(); i++) {
                    systemEq.setBandLevel(i, levels.get(i).shortValue());
                }
            }
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

        final short numBands = systemEq.getNumberOfBands();
        final int span = maxMb - minMb;

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

            if (label != null) {
                int centerFreqHz = systemEq.getCenterFreq(finalBand) / 1000;
                label.setText(centerFreqHz >= 1000 ? (centerFreqHz / 1000) + " kHz" : centerFreqHz + " Hz");
            }



            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (systemEq != null) {
                        int targetMb = minMb + progress;
                        systemEq.setBandLevel(finalBand, (short) targetMb);

                        if (currentEq != null && currentEq.getBandLevels() != null) {
                            currentEq.getBandLevels().set(finalBand, targetMb);
                        }
                    }


                    if (tooltip != null) {
                        int targetDb = (minMb + progress);
                        tooltip.setText(targetDb + " mB");
                        tooltip.setVisibility(View.VISIBLE);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });

            bandsContainer.addView(bandView);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (systemEq != null) {
            systemEq.release();
            systemEq = null;
        }
    }
}