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
    private DatabaseReference folderDbRef;
    private ValueEventListener presetsListener;
    private ValueEventListener foldersListener;

    public interface PresetsListener {
        void onPresetsLoaded(List<SelectedEqualizer> presets);
        void onError(Exception e);
    }

    public interface FoldersListener {
        void onFoldersLoaded(List<Folder> folders);
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
                DatabaseReference userRoot = FirebaseDatabase.getInstance()
                        .getReference("users")
                        .child(sanitizedEmail);
                this.userDbRef = userRoot.child("presets");
                this.folderDbRef = userRoot.child("folders");
                Log.d(TAG, "DataHandler configured safely for user: " + sanitizedEmail);
            } else {
                // Safe Fallback nodes to stop NullPointerException crashes
                this.userDbRef = FirebaseDatabase.getInstance().getReference("guest_presets");
                this.folderDbRef = FirebaseDatabase.getInstance().getReference("guest_folders");
                Log.w(TAG, "No authenticated user discovered. Pointing to guest fallback node.");
            }
        } catch (Exception e) {
            this.userDbRef = FirebaseDatabase.getInstance().getReference("error_fallback");
            this.folderDbRef = FirebaseDatabase.getInstance().getReference("error_fallback_folders");
            Log.e(TAG, "Failed initializing Firebase Reference structures", e);
        }
    }

    /**
     * Saves only the bandLevels field for a preset via a partial update
     * instead of overwriting the whole preset object. This is what
     * onStopTrackingTouch calls: cheaper than a full-object write for the
     * common "user moved a seekbar" case, and it structurally can't touch
     * name/artist/id even if the in-memory object were ever stale.
     */
    public void updateBandLevels(String presetId, List<Integer> bandLevels, OperationCallback callback) {
        if (presetId == null || bandLevels == null || bandLevels.isEmpty()) {
            if (callback != null) callback.onFailure(new IllegalArgumentException("Missing preset id or band levels"));
            return;
        }

        if (userDbRef == null) {
            if (callback != null) callback.onFailure(new IllegalStateException("Database reference missing"));
            return;
        }

        userDbRef.child(presetId).child("bandLevels").setValue(bandLevels)
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
        } else if (callback != null) {
            callback.onFailure(new IllegalStateException("Could not generate a preset ID"));
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

        presetsListener = new ValueEventListener() {
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
        };

        userDbRef.addValueEventListener(presetsListener);
    }

    /**
     * Creates a new folder, or updates an existing one if folder.getId() is
     * already set (used when re-importing a playlist that already has a
     * folder here, so re-import doesn't create a duplicate folder).
     */
    public void saveFolder(Folder folder, OperationCallback callback) {
        if (folder == null || folder.getName() == null) {
            if (callback != null) callback.onFailure(new IllegalArgumentException("Folder name is empty"));
            return;
        }

        if (folderDbRef == null) {
            if (callback != null) callback.onFailure(new IllegalStateException("Database reference missing"));
            return;
        }

        String folderId = folder.getId();
        if (folderId == null) {
            folderId = folderDbRef.push().getKey();
            if (folderId == null) {
                if (callback != null) callback.onFailure(new IllegalStateException("Could not generate a folder ID"));
                return;
            }
            folder.setId(folderId);
        }

        folderDbRef.child(folderId).setValue(folder)
                .addOnSuccessListener(aVoid -> {
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    if (callback != null) callback.onFailure(e);
                });
    }

    public void deleteFolder(Folder folder, OperationCallback callback) {
        if (folder == null || folder.getId() == null) {
            if (callback != null) callback.onFailure(new IllegalArgumentException("Invalid Folder ID"));
            return;
        }

        if (folderDbRef == null) {
            if (callback != null) callback.onFailure(new IllegalStateException("Database reference missing"));
            return;
        }

        folderDbRef.child(folder.getId()).removeValue()
                .addOnSuccessListener(aVoid -> {
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    if (callback != null) callback.onFailure(e);
                });
    }

    public void listenToFolders(FoldersListener listener) {
        if (folderDbRef == null) return;

        foldersListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                List<Folder> folderList = new ArrayList<>();
                for (DataSnapshot postSnapshot : snapshot.getChildren()) {
                    try {
                        Folder folder = postSnapshot.getValue(Folder.class);
                        if (folder != null) {
                            folder.setId(postSnapshot.getKey());
                            folderList.add(folder);
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Error parsing folder entry", e);
                    }
                }
                listener.onFoldersLoaded(folderList);
            }

            @Override
            public void onCancelled(DatabaseError error) {
                listener.onError(error.toException());
            }
        };

        folderDbRef.addValueEventListener(foldersListener);
    }

    /**
     * Detaches the presets and folders listeners. Call this from the
     * Fragment/Activity's onDestroyView (or equivalent) so Firebase stops
     * calling back into a destroyed UI and this handler doesn't keep it
     * alive indefinitely.
     */
    public void stopListening() {
        if (userDbRef != null && presetsListener != null) {
            userDbRef.removeEventListener(presetsListener);
            presetsListener = null;
        }
        if (folderDbRef != null && foldersListener != null) {
            folderDbRef.removeEventListener(foldersListener);
            foldersListener = null;
        }
    }
}