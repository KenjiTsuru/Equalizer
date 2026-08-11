package com.example.autoeq;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import com.google.firebase.auth.FirebaseAuth;

public class SettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        Button logoutBtn = view.findViewById(R.id.btn_logout);
        if (logoutBtn != null) {
            logoutBtn.setOnClickListener(v -> showLogoutConfirmation());
        }

        return view;
    }

    private void showLogoutConfirmation() {
        new AlertDialog.Builder(requireContext())
                .setTitle("Logout")
                .setMessage("Are you sure you want to logout?")
                .setPositiveButton("Yes", (dialog, which) -> {
                    performLogout();
                })
                .setNegativeButton("No", (dialog, which) -> {
                    dialog.dismiss();
                })
                .show();
    }

    private void performLogout() {
        // 1. Stop the always-on background service. Without this it kept
        // running indefinitely after logout - still holding the system-wide
        // EQ audio effect, the Spotify App Remote connection, and a Firebase
        // listener now pointed at a signed-out user - since nothing else in
        // the app ever stops it once started.
        requireContext().stopService(new Intent(requireContext(), SpotifyMonitorService.class));

        // 2. Log out from Firebase
        FirebaseAuth.getInstance().signOut();

        // 3. Navigate to LoginActivity and clear the activity stack
        Intent intent = new Intent(requireContext(), LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);

        // 4. Close the current activity - this also tears down
        // EqualizerEditorFragment (destroying it unbinds its ServiceConnection),
        // which is what lets the stopService() call above actually finish
        // destroying the service rather than being kept alive by that binding.
        if (getActivity() != null) {
            getActivity().finish();
        }
    }
}