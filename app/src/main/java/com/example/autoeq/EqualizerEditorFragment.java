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
        // Required empty public constructor
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
        // Inflate the layout for this fragment
        return inflater.inflate(R.layout.equalizer_fragment, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        DrawerLayout drawerLayout = view.findViewById(R.id.eq_drawer);
        MaterialToolbar toolbar = view.findViewById(R.id.eq_toolbar);
        NavigationView navView = view.findViewById(R.id.eq_nav_view);

        // Temporary built-in icon (works without adding drawables)
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size);

        // Listens for when toolbar is clicked
        toolbar.setNavigationOnClickListener(v ->
                drawerLayout.openDrawer(GravityCompat.START)
        );

        // Listens for whats selected in drawer
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

        // Equalizer editor logic
        SeekBar[] bars = new SeekBar[]{
                view.findViewById(R.id.seekBar1),
                view.findViewById(R.id.seekBar2),
                view.findViewById(R.id.seekBar3),
                view.findViewById(R.id.seekBar4)
        };

        int minMB = UI_MIN_DB;
        int maxMB = UI_MAX_DB;
        int span = maxMB - minMB;
        int centerProgress = -minMB;  // -> Value to start in the middle 0MB

        for(int i = 0; i < bars.length; i++) {
            SeekBar sb = bars[i];
            sb.setMax(span);
            sb.setProgress(centerProgress); // -> sets the default to 0MB

            final int bandIndex = i;

            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    if (!fromUser) return;

                    int targetMB = minMB + progress; // target mb within -1200 to 1200
                    float db = targetMB/100f; // converts to db -12.00 to 12.00

                    // TODO: Create actual logic for the equalizer not just UI
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {}
                @Override public void onStopTrackingTouch(SeekBar seekBar) {}
            });
        }
    }

    // Helper function for pop up menu to name your equalizer
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
}