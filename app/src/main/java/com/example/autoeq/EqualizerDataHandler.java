package com.example.autoeq;

import android.util.Log;
import com.google.android.gms.tasks.Task;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

    /** Bridges a Firebase write Task into an OperationCallback - shared by every write method below instead of each repeating the same addOnSuccessListener/addOnFailureListener pair. */
    private static void bridge(Task<Void> task, OperationCallback callback) {
        task.addOnSuccessListener(aVoid -> {
                    if (callback != null) callback.onSuccess();
                })
                .addOnFailureListener(e -> {
                    if (callback != null) callback.onFailure(e);
                });
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

        bridge(userDbRef.child(presetId).child("bandLevels").setValue(bandLevels), callback);
    }

    /**
     * Moves a preset to a different folder (or removes it from any folder if
     * folderId is null) via a partial update to just that field, rather than
     * rewriting the whole preset object.
     */
    public void updateFolderAssignment(String presetId, String folderId, OperationCallback callback) {
        if (presetId == null) {
            if (callback != null) callback.onFailure(new IllegalArgumentException("Missing preset id"));
            return;
        }

        if (userDbRef == null) {
            if (callback != null) callback.onFailure(new IllegalStateException("Database reference missing"));
            return;
        }

        bridge(userDbRef.child(presetId).child("folderId").setValue(folderId), callback);
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
            bridge(userDbRef.child(presetId).setValue(equalizer), callback);
        } else if (callback != null) {
            callback.onFailure(new IllegalStateException("Could not generate a preset ID"));
        }
    }

    /** A local, network-free id for a not-yet-saved preset - same push-key generator saveEqualizer uses internally, exposed so batch callers (playlist import) can assign ids up front for cross-referencing (e.g. linking duplicates within the same import) before the actual write happens. */
    public String generatePresetId() {
        return userDbRef != null ? userDbRef.push().getKey() : null;
    }

    /**
     * Writes many presets in one multi-location update instead of one
     * setValue() per preset. Each write to a child under userDbRef re-fires
     * the whole-list listenToPresets listener - saving N presets one at a
     * time means N full list reloads (each triggering a full drawer
     * rebuild), which is what made large playlist imports freeze the UI.
     * One batched update here means exactly one reload. Every equalizer
     * must already have its id set (see generatePresetId).
     */
    public void saveEqualizers(List<SelectedEqualizer> equalizers, OperationCallback callback) {
        if (equalizers == null || equalizers.isEmpty()) {
            if (callback != null) callback.onSuccess();
            return;
        }

        if (userDbRef == null) {
            if (callback != null) callback.onFailure(new IllegalStateException("Database reference missing"));
            return;
        }

        Map<String, Object> updates = new HashMap<>();
        for (SelectedEqualizer eq : equalizers) {
            if (eq.getId() == null) continue;
            updates.put(eq.getId(), eq);
        }

        bridge(userDbRef.updateChildren(updates), callback);
    }

    /** Deletes many presets in one multi-location update - see saveEqualizers for why this matters for multi-select delete. Setting a child to null via updateChildren deletes it, same as removeValue(). */
    public void deleteEqualizers(List<String> presetIds, OperationCallback callback) {
        if (presetIds == null || presetIds.isEmpty()) {
            if (callback != null) callback.onSuccess();
            return;
        }

        if (userDbRef == null) {
            if (callback != null) callback.onFailure(new IllegalStateException("Database reference missing"));
            return;
        }

        Map<String, Object> updates = new HashMap<>();
        for (String id : presetIds) {
            updates.put(id, null);
        }

        bridge(userDbRef.updateChildren(updates), callback);
    }

    /** Deletes many folders in one multi-location update - see saveEqualizers for why this matters for multi-select delete. */
    public void deleteFolders(List<String> folderIds, OperationCallback callback) {
        if (folderIds == null || folderIds.isEmpty()) {
            if (callback != null) callback.onSuccess();
            return;
        }

        if (folderDbRef == null) {
            if (callback != null) callback.onFailure(new IllegalStateException("Database reference missing"));
            return;
        }

        Map<String, Object> updates = new HashMap<>();
        for (String id : folderIds) {
            updates.put(id, null);
        }

        bridge(folderDbRef.updateChildren(updates), callback);
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

        bridge(folderDbRef.child(folderId).setValue(folder), callback);
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