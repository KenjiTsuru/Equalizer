package com.example.autoeq;

import com.example.autoeq.models.User;
import com.google.android.gms.tasks.Task;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

public class FirebaseCRUD {
    private DatabaseReference mDatabase;

    public FirebaseCRUD() {
        try {
            // Updated to use the correct -default-rtdb URL from your google-services.json
            mDatabase = FirebaseDatabase.getInstance("https://equalizer-b237a-default-rtdb.firebaseio.com/").getReference();
        } catch (Exception e) {
            mDatabase = FirebaseDatabase.getInstance().getReference();
        }
    }

    public Task<Void> addOrUpdateUser(String userId, User user) {
        return mDatabase.child("users").child(userId).setValue(user);
    }

    public Task<DataSnapshot> getUserSnapshot(String userId) {
        return mDatabase.child("users").child(userId).get();
    }

    public DatabaseReference getUserReference(String userId) {
        return mDatabase.child("users").child(userId);
    }
}
