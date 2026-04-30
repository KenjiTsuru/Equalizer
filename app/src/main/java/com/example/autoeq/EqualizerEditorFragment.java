package com.example.autoeq;

import android.app.AlertDialog;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;
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
import java.util.Map;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link EqualizerEditorFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class EqualizerEditorFragment extends Fragment {

    private Equalizer systemEq;
    private NavigationView navView;
    private short minMb, maxMb;
    private LinearLayout bandsContainer;
    private ArrayList<GenreEqualizer> presets = new ArrayList<>();

    private int nextPresetMenuId = 1000;
    private final java.util.Map<Integer, GenreEqualizer> presetsByMenuId = new java.util.LinkedHashMap<>();

    private View emptyStateText;
    private View eqUiContainer;

    // TODO: Rename parameter arguments, choose names that match
    // the fragment initialization parameters, e.g. ARG_ITEM_NUMBER
    private static final String ARG_PARAM1 = "param1";
    private static final String ARG_PARAM2 = "param2";

    // TODO: Rename and change types of parameters
    private String mParam1;
    private String mParam2;

    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment EqualizerEditorFragment.
     */
    // TODO: Rename and change types and number of parameters
    public static EqualizerEditorFragment newInstance(String param1, String param2) {
        EqualizerEditorFragment fragment = new EqualizerEditorFragment();
        Bundle args = new Bundle();
        args.putString(ARG_PARAM1, param1);
        args.putString(ARG_PARAM2, param2);
        fragment.setArguments(args);
        return fragment;
    }

    public EqualizerEditorFragment() {
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mParam1 = getArguments().getString(ARG_PARAM1);
            mParam2 = getArguments().getString(ARG_PARAM2);
        }
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.equalizer_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        DrawerLayout drawerLayout = view.findViewById(R.id.eq_drawer);
        MaterialToolbar toolbar = view.findViewById(R.id.eq_toolbar);
        navView = view.findViewById(R.id.eq_nav_view);
        emptyStateText = view.findViewById(R.id.eq_empty_state_text);
        eqUiContainer = view.findViewById(R.id.equalizer_ui_container);

        toolbar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size);

        toolbar.setNavigationOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.START)
        );

        // Setup the new create button in the drawer header
        View headerView = navView.getHeaderView(0);
        View btnCreate = headerView.findViewById(R.id.btn_create_eq);
        if (btnCreate != null) {
            btnCreate.setOnClickListener(v -> {
                drawerLayout.closeDrawer(GravityCompat.START);
                showCreateEqualizerDialog();
            });
        }

        navView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (presetsByMenuId.containsKey(id)) {
                GenreEqualizer selectedEq = presetsByMenuId.get(id);
                // implement applyPreset
                drawerLayout.closeDrawer(GravityCompat.START);
                return true;
            }

            drawerLayout.closeDrawer(GravityCompat.START);
            return false;
        });


        bandsContainer = view.findViewById(R.id.eq_bands_row);

        initSystemEqualizer(0);
        
        // Only show UI if we already have presets
        if (!presets.isEmpty()) {
            showEqualizerUi();
        }
    }

    private void showEqualizerUi() {
        if (emptyStateText != null) emptyStateText.setVisibility(View.GONE);
        if (eqUiContainer != null) eqUiContainer.setVisibility(View.VISIBLE);
        if (systemEq != null && bandsContainer.getChildCount() == 0) {
            buildBandUiFromSystemEqualizer();
        }
    }

    // Helper function for pop up menu to name your equalizer
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
                if (position == 0) { // Song and Artist
                    songNameInput.setHint("Song Name");
                    artistNameInput.setVisibility(View.VISIBLE);
                } else { // Genre
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

                    short type = (short) typeSpinner.getSelectedItemPosition();

                    int numBands = (systemEq != null) ? systemEq.getNumberOfBands() : 5;
                    GenreEqualizer eq = new GenreEqualizer(name, artist, type, new int[numBands], new short[numBands]);
                    presets.add(eq);
                    presetsByMenuId.put(nextPresetMenuId, eq);
                    nextPresetMenuId++;

                    updateDrawerMenu(navView);
                    showEqualizerUi();

                    Toast.makeText(requireContext(), "Created: " + eq.getDisplayName(), Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void updateDrawerMenu(NavigationView navView) {
        android.view.Menu menu = navView.getMenu();

        for (int i = 1000; i < nextPresetMenuId; i++) {
            menu.removeItem(i);
        }

        int groupId = 2;
        for (int i = 0; i < presets.size(); i++) {
            GenreEqualizer eq = presets.get(i);
            int menuId = -1;
            for (java.util.Map.Entry<Integer, GenreEqualizer> entry : presetsByMenuId.entrySet()) {
                if (entry.getValue().equals(eq)) {
                    menuId = entry.getKey();
                    break;
                }
            }
            if (menuId != -1) {
                menu.add(groupId, menuId, android.view.Menu.NONE, eq.getDisplayName()).setIcon(android.R.drawable.ic_media_next);
            }
        }
    }

    private void initSystemEqualizer(int audioSessionId) {
        try {
            systemEq = new Equalizer(0, audioSessionId);
            systemEq.setEnabled(true);

            short[] range = systemEq.getBandLevelRange(); // millibels
            minMb = range[0];
            maxMb = range[1];

        } catch (Throwable t) {
            systemEq = null;
            Toast.makeText(requireContext(),
                    "Equalizer not supported on this device/session: " + t.getClass().getSimpleName(),
                    Toast.LONG).show();
        }
    }

    private void buildBandUiFromSystemEqualizer() {
        bandsContainer.removeAllViews();

        final short numBands = systemEq.getNumberOfBands();
        final int span = maxMb - minMb;

        for (short band = 0; band < numBands; band++) {
            final short finalBand = band;

            View bandView = LayoutInflater.from(requireContext())
                    .inflate(R.layout.equalizer_band_item, bandsContainer, false);

            VerticalSeekBar sb = bandView.findViewById(R.id.eq_band_seekbar);

            sb.setMax(span);
            short currentMb = systemEq.getBandLevel(finalBand);
            // Centering progress for visualization if needed, or use currentMb
            sb.setProgress(currentMb - minMb);

            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser || systemEq == null) return;
                    int targetMb = minMb + progress;
                    systemEq.setBandLevel(finalBand, (short) targetMb);
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
