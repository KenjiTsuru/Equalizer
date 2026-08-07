package com.example.autoeq;

import android.util.Log;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.List;

public class EqualizerDataHandler {
    private static final String TAG = "EqualizerDataHandler";
    private DatabaseReference userDbRef;

    public interface PresetsListener {
        void onPresetsLoaded(List<SelectedEqualizer> presets);
        void onError(Exception e);
    }

    public interface OperationCallback {
        void onSuccess();
        void onFailure(Exception e);
    }

    public EqualizerDataHandler() {
        try {
            FirebaseUser user = FirebaseAuth.getInstance().getCurrentUser();
            if (user != null && user.getEmail() != null) {
                // Sanitize email string safely
                String sanitizedEmail = user.getEmail().replace(".", "_");
                this.userDbRef = FirebaseDatabase.getInstance()
                        .getReference("users")
                        .child(sanitizedEmail)
                        .child("presets");
                Log.d(TAG, "DataHandler configured safely for user: " + sanitizedEmail);
            } else {
                // Safe Fallback node to stop NullPointerException crashes
                this.userDbRef = FirebaseDatabase.getInstance().getReference("guest_presets");
                Log.w(TAG, "No authenticated user discovered. Pointing to guest fallback node.");
            }
        } catch (Exception e) {
            this.userDbRef = FirebaseDatabase.getInstance().getReference("error_fallback");
            Log.e(TAG, "Failed initializing Firebase Reference structures", e);
        }
    }

    /**
     * Updates an existing equalizer preset in Firebase.
     * This is used when moving seekbars/sliders.
     */
    public void updateEqualizer(SelectedEqualizer equalizer, OperationCallback callback) {
        if (equalizer == null || equalizer.getId() == null) {
            if (callback != null) callback.onFailure(new Exception("Cannot update: ID is missing"));
            return;
        }

        if (userDbRef == null) {
            if (callback != null) callback.onFailure(new Exception("Database reference missing"));
            return;
        }

        // Target the specific ID of the preset and overwrite it with the new levels
        userDbRef.child(equalizer.getId()).setValue(equalizer)
                .addOnSuccessListener(aVoid -> {
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    if (callback != null) callback.onFailure(e);
                });
    }

    public void saveEqualizer(SelectedEqualizer equalizer, OperationCallback callback) {
        if (equalizer == null || equalizer.getName() == null) {
            if (callback != null) callback.onFailure(new IllegalArgumentException("Equalizer data empty"));
            return;
        }

        if (userDbRef == null) {
            if (callback != null) callback.onFailure(new IllegalStateException("Database reference missing"));
            return;
        }

        String presetId = userDbRef.push().getKey();
        if (presetId != null) {
            equalizer.setId(presetId);

            userDbRef.child(presetId).setValue(equalizer)
                    .addOnSuccessListener(aVoid -> {
                        if (callback != null) callback.onSuccess();
                    })
                    .addOnFailureListener(e -> {
                        if (callback != null) callback.onFailure(e);
                    });
        }
    }

    public void deleteEqualizer(SelectedEqualizer equalizer, OperationCallback callback) {    if (equalizer == null || equalizer.getId() == null) {
        if (callback != null) callback.onFailure(new IllegalArgumentException("Invalid Preset ID"));
        return;
    }

        if (userDbRef == null) {
            if (callback != null) callback.onFailure(new IllegalStateException("Database reference missing"));
            return;
        }

        // Use .child(id).removeValue() to delete the specific preset
        userDbRef.child(equalizer.getId()).removeValue()
                .addOnSuccessListener(aVoid -> {
                    Log.d(TAG, "Preset deleted successfully: " + equalizer.getName());
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to delete preset", e);
                    if (callback != null) callback.onFailure(e);
                });
    }

    public void listenToPresets(PresetsListener listener) {
        if (userDbRef == null) return;

        userDbRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                List<SelectedEqualizer> presetList = new ArrayList<>();
                for (DataSnapshot postSnapshot : snapshot.getChildren()) {
                    try {
                        SelectedEqualizer eq = postSnapshot.getValue(SelectedEqualizer.class);
                        if (eq != null) {
                            eq.setId(postSnapshot.getKey());
                            presetList.add(eq);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error compiling single data entry schema parsing stream", e);
                    }
                }
                listener.onPresetsLoaded(presetList);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                listener.onError(error.toException());
            }
        });
    }
}