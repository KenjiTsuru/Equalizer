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
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Toast;
import android.media.audiofx.Equalizer;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link EqualizerEditorFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class EqualizerEditorFragment extends Fragment {
    private static final int UI_MIN_DB = -1200;
    private static final int UI_MAX_DB = 1200;

    private Equalizer systemEq;
    private short minMb, maxMb;
    private LinearLayout bandsContainer;

    private int nextPresetMenuId = 1000;
    private final java.util.Map<Integer, GenreEqualizer> presetsByMenuId = new java.util.LinkedHashMap<>();

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
        NavigationView navView = view.findViewById(R.id.eq_nav_view);

        toolbar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size);

        toolbar.setNavigationOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.START)
        );

        navView.setNavigationItemSelectedListener(item -> {
            int id = item.getItemId();

            if (id == R.id.drawer_create_equalizer) {
                drawerLayout.closeDrawer(GravityCompat.START);
                showCreateEqualizerDialog();
                return true;
            }

            if (id == R.id.drawer_manage_equalizers) {
                drawerLayout.closeDrawer(GravityCompat.START);
                Toast.makeText(requireContext(), "Manage equalizers (TODO)", Toast.LENGTH_SHORT).show();
                return true;
            }

            drawerLayout.closeDrawer(GravityCompat.START);
            return false;
        });


        bandsContainer = view.findViewById(R.id.eq_bands_row);

        initSystemEqualizer(0);
        if (systemEq != null) {
            buildBandUiFromSystemEqualizer();
        }

    }

    private void showCreateEqualizerDialog() {
        EditText input = new EditText(requireContext());
        input.setHint("Equalizer genre");

        new AlertDialog.Builder(requireContext())
                .setTitle("Create your equalizer")
                .setMessage("Type equalizer genre:")
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) name = "Untitled";

                    // TODO: hook into real equalizer creation logic
                    Toast.makeText(requireContext(), "Created: " + name, Toast.LENGTH_SHORT).show();
                })
                .show();
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
                    Toast.LENGTH_LONG).show();
        }
    }

    private void buildBandUiFromSystemEqualizer() {
        bandsContainer.removeAllViews();

        final short numBands = systemEq.getNumberOfBands();
        final int span = maxMb - minMb;

        for (short band = 0; band < numBands; band++) {
            final short finalBand = band;

            View row = LayoutInflater.from(requireContext())
                    .inflate(android.R.layout.simple_list_item_2, bandsContainer, false);

            TextView title = row.findViewById(android.R.id.text1);
            TextView subtitle = row.findViewById(android.R.id.text2);

            int centerFreqmHz = systemEq.getCenterFreq(finalBand); // milliHertz
            float centerHz = centerFreqmHz / 1000f;

            title.setText("Band " + finalBand);
            subtitle.setText(String.format(java.util.Locale.US, "%.0f Hz", centerHz));

            SeekBar sb = new SeekBar(requireContext());
            sb.setMax(span);

            short currentMb = systemEq.getBandLevel(finalBand);
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

            LinearLayout wrapper = new LinearLayout(requireContext());
            wrapper.setOrientation(LinearLayout.VERTICAL);
            int padding = getResources().getDimensionPixelSize(R.dimen.band_vertical_padding);
            wrapper.setPadding(0, padding, 0, padding);

            wrapper.addView(row);
            wrapper.addView(sb);

            bandsContainer.addView(wrapper);
        }
    }

    private GenreEqualizer snapshotCurrentPreset(String name) {
        short numBands = systemEq.getNumberOfBands();
        int[] freqs = new int[numBands];
        short[] levels = new short[numBands];

        for (short band = 0; band < numBands; band++) {
            freqs[band] = systemEq.getCenterFreq(band);
            levels[band] = systemEq.getBandLevel(band);
        }
        return new GenreEqualizer(name, freqs, levels);
    }

    private void applyPresetToDevice(GenreEqualizer preset) {
        if (systemEq == null) return;

        short deviceBands = systemEq.getNumberOfBands();
        for (short band = 0; band < deviceBands; band++) {
            int deviceFreq = systemEq.getCenterFreq(band);

            short targetLevel = nearestLevelForFreq(preset, deviceFreq);
            systemEq.setBandLevel(band, targetLevel);
        }

        buildBandUiFromSystemEqualizer();
    }


    private short nearestLevelForFreq(GenreEqualizer preset, int deviceFreqmHz) {
        int[] freqs = preset.getCenterFreqmHz();
        short[] levels = preset.getLevelsMb();

        int bestIdx = 0;
        long bestDist = Long.MAX_VALUE;

        for (int i = 0; i < freqs.length; i++) {
            long d = Math.abs((long) freqs[i] - deviceFreqmHz);
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }
        return levels[bestIdx];
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
