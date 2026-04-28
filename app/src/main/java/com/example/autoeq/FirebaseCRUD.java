package com.example.autoeq;

import com.example.autoeq.models.User;
import com.google.android.gms.tasks.Task;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;

import java.util.List;

public class FirebaseCRUD {
    private DatabaseReference mDatabase;

    public FirebaseCRUD() {
        mDatabase = FirebaseDatabase.getInstance().getReference();
    }

    // --- User Profile CRUD ---

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