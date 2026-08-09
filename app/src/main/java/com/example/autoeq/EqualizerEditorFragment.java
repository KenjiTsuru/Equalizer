package com.example.autoeq;

import android.app.AlertDialog;
import android.media.audiofx.DynamicsProcessing;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
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
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class EqualizerEditorFragment extends Fragment {

    // EQ shape: 12 bands, log-spaced 30 Hz-8000 Hz, +/-12 dB range. Stored and
    // passed around as tenths-of-a-dB integers (e.g. 35 = 3.5 dB) so
    // SelectedEqualizer's List<Integer> and the SeekBar's integer progress
    // don't need to change shape just because DynamicsProcessing's native
    // unit is a float dB rather than the old Equalizer's millibel short.
    private static final int NUM_BANDS = 12;
    private static final float[] BAND_FREQUENCIES_HZ = {
            30f, 50f, 83f, 138f, 229f, 380f, 632f, 1050f, 1744f, 2900f, 4800f, 8000f
    };
    private static final float MIN_GAIN_DB = -12f;
    private static final float MAX_GAIN_DB = 12f;
    private static final int MIN_LEVEL = Math.round(MIN_GAIN_DB * 10);
    private static final int MAX_LEVEL = Math.round(MAX_GAIN_DB * 10);
    private static final int SPAN = MAX_LEVEL - MIN_LEVEL;

    private DynamicsProcessing systemEq;
    private SelectedEqualizer currentEq;
    private NavigationView navView;
    private LinearLayout bandsContainer;

    // Firebase Data Handler reference replaces local indexing pools
    private EqualizerDataHandler dataHandler;
    private List<SelectedEqualizer> presets = new ArrayList<>();
    private List<Folder> folders = new ArrayList<>();

    // Drawer state: id of the one folder currently expanded (its presets show
    // indented directly below it), or null if none are expanded.
    private String expandedFolderId = null;
    // Maps a rendered drawer menu item's id back to what it represents -
    // a Folder or a SelectedEqualizer - since NavigationView's Menu only
    // gives us the clicked item's id/title, not an arbitrary object.
    private final Map<Integer, Object> menuItemTargets = new HashMap<>();

    private SpotifyWebApiClient spotifyWebApiClient;

    private View emptyStateText;
    private View eqUiContainer;
    private MaterialToolbar toolbar;
    private TextView presetNameText;
    private TextView sharedTooltip;

    // Debounced save: onProgressChanged is the confirmed-firing callback, so
    // it's what actually schedules the Firebase write. Every progress change
    // resets this timer; the write only goes out once movement pauses.
    private final Handler saveHandler = new Handler(Looper.getMainLooper());
    private final Runnable pendingBandLevelSave = this::persistCurrentBandLevels;
    private static final long BAND_LEVEL_SAVE_DEBOUNCE_MS = 400;

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
        presetNameText = view.findViewById(R.id.eq_preset_name);
        sharedTooltip = view.findViewById(R.id.eq_shared_tooltip);

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
                    String query = s.toString();
                    if (query.isEmpty()) {
                        updateDrawerMenu();
                    } else {
                        filterDrawerMenu(query);
                    }
                }
                @Override
                public void afterTextChanged(android.text.Editable s) {}
            });
        }

        if (btnCreate != null) {
            btnCreate.setOnClickListener(v -> {
                drawerLayout.closeDrawer(GravityCompat.START);
                showCreateChooserDialog();
            });
        }

        dataHandler = new EqualizerDataHandler();

        // Handle navigation items by looking up what the tapped item represents
        // (folder / preset / back) instead of matching on title text, since
        // folder and preset names could otherwise collide.
        navView.setNavigationItemSelectedListener(item -> {
            Object target = menuItemTargets.get(item.getItemId());

            if (target instanceof SelectedEqualizer) {
                applySelectedPreset((SelectedEqualizer) target);
                drawerLayout.closeDrawer(GravityCompat.START);
                return true;
            } else if (target instanceof Folder) {
                String tappedId = ((Folder) target).getId();
                expandedFolderId = tappedId.equals(expandedFolderId) ? null : tappedId;
                // NavigationView suspends its own menu-refresh logic while
                // still inside this click callback - rebuilding synchronously
                // here updates expandedFolderId and the underlying Menu data
                // correctly, but the visible list silently doesn't reflect it
                // until something else triggers a redraw. Posting defers the
                // rebuild until after this callback returns, once that
                // suspension has lifted.
                navView.post(this::updateDrawerMenu);
                return true;
            }

            drawerLayout.closeDrawer(GravityCompat.START);
            return false;
        });

        initSystemEqualizer(0);

        // Global on/off for the system equalizer effect - not tied to any preset.
        SwitchCompat powerSwitch = view.findViewById(R.id.eq_power_switch);
        if (powerSwitch != null) {
            powerSwitch.setEnabled(systemEq != null);
            powerSwitch.setChecked(systemEq != null && systemEq.getEnabled());
            powerSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (systemEq != null) {
                    systemEq.setEnabled(isChecked);
                }
            });
        }

        View settingsButton = view.findViewById(R.id.eq_settings_button);
        if (settingsButton != null) {
            settingsButton.setOnClickListener(v -> {
                requireActivity().getSupportFragmentManager().beginTransaction()
                        .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out, android.R.anim.fade_in, android.R.anim.fade_out)
                        .hide(this)
                        .add(R.id.equalizer_fragment_container, new SettingsFragment())
                        .addToBackStack(null)
                        .commit();
            });
        }

        // Start listening directly to Firebase node updates
        dataHandler.listenToPresets(new EqualizerDataHandler.PresetsListener() {
            @Override
            public void onPresetsLoaded(List<SelectedEqualizer> updatedPresets) {
                if (!isAdded()) return;

                String currentId = currentEq != null ? currentEq.getId() : null;
                int oldIndex = -1;

                // Find the index of the current item in the old list (if it exists)
                if (currentId != null) {
                    for (int i = 0; i < presets.size(); i++) {
                        if (presets.get(i).getId().equals(currentId)) {
                            oldIndex = i;
                            break;
                        }
                    }
                }

                presets = updatedPresets;

                String currentQuery = searchBar != null ? searchBar.getText().toString() : "";
                if (currentQuery.isEmpty()) {
                    updateDrawerMenu();
                } else {
                    filterDrawerMenu(currentQuery);
                }

                if (!presets.isEmpty()) {
                    showEqualizerUi();

                    boolean stillExists = false;

                    for(SelectedEqualizer eq : presets) {
                        if (currentId != null && currentId.equals(eq.getId())){
                            stillExists = true;
                            break;
                        }
                    }


                    if (!stillExists && currentId != null) {
                        int newIndex;
                        if (oldIndex < presets.size()) {
                            // Switch to the next one in line (which now occupies the old index)
                            newIndex = oldIndex;
                        } else {
                            // It was the last one in the list, switch to the new last one (above it)
                            newIndex = presets.size() - 1;
                        }

                        if (newIndex >= 0) {
                            applySelectedPreset(presets.get(newIndex));
                        }
                    } else if (currentEq == null) {
                        // Standard fallback for initial load
                        applySelectedPreset(presets.get(0));
                    }

                } else {
                    currentEq = null;
                    updateCurrentEqDisplay();
                    if (emptyStateText != null) emptyStateText.setVisibility(View.VISIBLE);
                    if (eqUiContainer != null) eqUiContainer.setVisibility(View.GONE);
                }
            }

            @Override
            public void onError(Exception e) {
                if (!isAdded()) return;
                if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
                    Toast.makeText(getContext(), "Database Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });

        dataHandler.listenToFolders(new EqualizerDataHandler.FoldersListener() {
            @Override
            public void onFoldersLoaded(List<Folder> updatedFolders) {
                if (!isAdded()) return;
                folders = updatedFolders;

                String currentQuery = searchBar != null ? searchBar.getText().toString() : "";
                if (currentQuery.isEmpty()) {
                    updateDrawerMenu();
                }
                // Folders never show up in search results, so no refresh needed there.
            }

            @Override
            public void onError(Exception e) {
                if (!isAdded()) return;
                if (com.google.firebase.auth.FirebaseAuth.getInstance().getCurrentUser() != null) {
                    Toast.makeText(getContext(), "Folder Database Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    /**
     * Derives a menu item id from the object's own permanent Firebase key
     * instead of its position in the list. Position-based ids (a counter
     * starting fresh each render) break as soon as the list reorders itself
     * - e.g. expanding a folder inserts items above others, so whatever used
     * to be at id 2002 is a different item after the rebuild. A hash of the
     * item's own stable id means the same folder or preset always gets the
     * same menu id no matter how many times the list above it has changed.
     */
    private static int stableMenuItemId(String firebaseId) {
        return firebaseId.hashCode() & 0x7FFFFFFF;
    }

    /**
     * Renders the search-filtered view: presets only, matched by name/artist,
     * searched across ALL presets regardless of folder or which folder is
     * currently open. Folders themselves are never matched or shown here.
     */
    private void filterDrawerMenu(String query) {
        android.view.Menu menu = navView.getMenu();
        menu.clear();
        menuItemTargets.clear();

        int groupId = 2;
        menu.setGroupCheckable(groupId, false, false);

        for (SelectedEqualizer eq : presets) {
            // Only add items that match the search query (case-insensitive)
            if (eq.getDisplayName().toLowerCase().contains(query.toLowerCase())) {
                int presetMenuId = stableMenuItemId(eq.getId());
                android.view.MenuItem item = menu.add(groupId, presetMenuId, android.view.Menu.NONE, eq.getDisplayName())
                        .setIcon(android.R.drawable.ic_media_next);

                item.setActionView(R.layout.menu_delete_action);
                View deleteBtn = item.getActionView().findViewById(R.id.btn_delete_preset);
                deleteBtn.setOnClickListener(v -> showDeleteConfirmationDialog(eq));

                menuItemTargets.put(presetMenuId, eq);
            }
        }
    }

    private void applySelectedPreset(SelectedEqualizer eq) {
        currentEq = eq;
        updateCurrentEqDisplay();

        if (systemEq != null && currentEq != null) {
            List<Integer> levels = currentEq.getBandLevels();

            // levels can come back null or shorter than NUM_BANDS - a preset saved
            // before this fix, a Firebase read that hasn't fully resolved yet, or
            // an old preset from before the 12-band migration. Always fully
            // resync every band instead of skipping, defaulting anything missing
            // to 0 dB, and write the result back onto currentEq so the in-memory
            // model is never null/short going forward.
            List<Integer> safeLevels = new ArrayList<>(NUM_BANDS);
            for (int i = 0; i < NUM_BANDS; i++) {
                int level = (levels != null && i < levels.size() && levels.get(i) != null) ? levels.get(i) : 0;
                safeLevels.add(level);
                systemEq.setPreEqBandAllChannelsTo(i,
                        new DynamicsProcessing.EqBand(true, BAND_FREQUENCIES_HZ[i], levelToGainDb(level)));
            }
            currentEq.setBandLevels(safeLevels);

            // Redraw layout tracks to fit the loaded properties
            buildBandUiFromSystemEqualizer();
        }
    }

    private void updateCurrentEqDisplay() {
        if (presetNameText != null) {
            presetNameText.setText(currentEq != null ? currentEq.getDisplayName() : "");
        }
    }

    private void showEqualizerUi() {
        if (emptyStateText != null) emptyStateText.setVisibility(View.GONE);
        if (eqUiContainer != null) eqUiContainer.setVisibility(View.VISIBLE);
        if (systemEq != null && bandsContainer.getChildCount() == 0) {
            buildBandUiFromSystemEqualizer();
        }
    }

    /** The "+" button: choose what to add. */
    private void showCreateChooserDialog() {
        String[] options = {"New Preset", "New Folder", "Import Playlist"};
        new AlertDialog.Builder(requireContext())
                .setTitle("Add New")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) showCreateEqualizerDialog();
                    else if (which == 1) showCreateFolderDialog();
                    else showImportPlaylistDialog();
                })
                .setNegativeButton("Cancel", null)
                .show();
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

                    if (isDuplicatePreset(name, artist, type, presets)) {
                        Toast.makeText(requireContext(), "A preset for \"" + name + "\" already exists", Toast.LENGTH_LONG).show();
                        return;
                    }

                    // Build modern dynamic generic collection arrays explicitly
                    List<Integer> bandIds = new ArrayList<>();
                    List<Integer> initialLevels = new ArrayList<>();

                    for (int i = 0; i < NUM_BANDS; i++) {
                        bandIds.add(i);
                        initialLevels.add(0);
                    }

                    SelectedEqualizer eq = new SelectedEqualizer(name, artist, type, bandIds, initialLevels);
                    eq.setFolderId(expandedFolderId);

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

    private void showCreateFolderDialog() {
        final EditText folderNameInput = new EditText(requireContext());
        folderNameInput.setHint("Folder Name");

        new AlertDialog.Builder(requireContext())
                .setTitle("Create Folder")
                .setView(folderNameInput)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = folderNameInput.getText().toString().trim();
                    if (name.isEmpty()) name = "Untitled Folder";

                    Folder folder = new Folder(name, null);

                    if (dataHandler == null) {
                        dataHandler = new EqualizerDataHandler();
                    }

                    dataHandler.saveFolder(folder, new EqualizerDataHandler.OperationCallback() {
                        @Override
                        public void onSuccess() {
                            if (isAdded() && getActivity() != null) {
                                getActivity().runOnUiThread(() ->
                                        Toast.makeText(requireContext(), "Folder created: " + folder.getName(), Toast.LENGTH_SHORT).show());
                            }
                        }

                        @Override
                        public void onFailure(Exception e) {
                            if (isAdded() && getActivity() != null) {
                                getActivity().runOnUiThread(() ->
                                        Toast.makeText(requireContext(), "Folder Create Error: " + e.getMessage(), Toast.LENGTH_LONG).show());
                            }
                        }
                    });
                })
                .show();
    }

    private void showImportPlaylistDialog() {
        if (!(requireActivity() instanceof MainActivity)) {
            Toast.makeText(requireContext(), "Can't import playlists from this screen", Toast.LENGTH_SHORT).show();
            return;
        }
        MainActivity activity = (MainActivity) requireActivity();

        if (spotifyWebApiClient == null) {
            spotifyWebApiClient = new SpotifyWebApiClient();
        }

        Toast.makeText(requireContext(), "Connecting to Spotify...", Toast.LENGTH_SHORT).show();

        activity.requestSpotifyWebApiToken(new MainActivity.SpotifyTokenCallback() {
            @Override
            public void onTokenReady(String accessToken) {
                spotifyWebApiClient.fetchUserPlaylists(accessToken, new SpotifyWebApiClient.PlaylistsCallback() {
                    @Override
                    public void onSuccess(List<SpotifyWebApiClient.SpotifyPlaylist> spotifyPlaylists) {
                        if (!isAdded() || getActivity() == null) return;
                        getActivity().runOnUiThread(() -> showPlaylistPickerDialog(accessToken, spotifyPlaylists));
                    }

                    @Override
                    public void onFailure(Exception e) {
                        if (!isAdded() || getActivity() == null) return;
                        getActivity().runOnUiThread(() ->
                                Toast.makeText(requireContext(), "Could not load playlists: " + e.getMessage(), Toast.LENGTH_LONG).show());
                    }
                });
            }

            @Override
            public void onTokenError(String message) {
                if (!isAdded() || getActivity() == null) return;
                getActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "Spotify login failed: " + message, Toast.LENGTH_LONG).show());
            }
        });
    }

    private void showPlaylistPickerDialog(String accessToken, List<SpotifyWebApiClient.SpotifyPlaylist> spotifyPlaylists) {
        if (spotifyPlaylists.isEmpty()) {
            Toast.makeText(requireContext(), "No playlists found on this Spotify account", Toast.LENGTH_LONG).show();
            return;
        }

        String[] names = new String[spotifyPlaylists.size()];
        for (int i = 0; i < spotifyPlaylists.size(); i++) {
            names[i] = spotifyPlaylists.get(i).name;
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("Choose a playlist")
                .setItems(names, (dialog, which) -> importPlaylist(accessToken, spotifyPlaylists.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void importPlaylist(String accessToken, SpotifyWebApiClient.SpotifyPlaylist playlist) {
        Toast.makeText(requireContext(), "Importing \"" + playlist.name + "\"...", Toast.LENGTH_SHORT).show();

        spotifyWebApiClient.fetchPlaylistTracks(accessToken, playlist.id, new SpotifyWebApiClient.TracksCallback() {
            @Override
            public void onSuccess(List<SpotifyWebApiClient.SpotifyTrack> tracks) {
                if (!isAdded() || getActivity() == null) return;
                getActivity().runOnUiThread(() -> finishPlaylistImport(playlist, tracks));
            }

            @Override
            public void onFailure(Exception e) {
                if (!isAdded() || getActivity() == null) return;
                getActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(), "Could not load playlist tracks: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void finishPlaylistImport(SpotifyWebApiClient.SpotifyPlaylist playlist, List<SpotifyWebApiClient.SpotifyTrack> tracks) {
        if (dataHandler == null) {
            dataHandler = new EqualizerDataHandler();
        }

        // Reuse an existing folder tied to this exact Spotify playlist rather
        // than creating a duplicate one on repeat imports.
        Folder existingFolder = null;
        for (Folder folder : folders) {
            if (playlist.id.equals(folder.getSpotifyPlaylistId())) {
                existingFolder = folder;
                break;
            }
        }

        Folder folder = existingFolder != null ? existingFolder : new Folder(playlist.name, playlist.id);
        Folder finalFolder = folder;

        dataHandler.saveFolder(folder, new EqualizerDataHandler.OperationCallback() {
            @Override
            public void onSuccess() {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> importTracksIntoFolder(finalFolder, tracks));
                }
            }

            @Override
            public void onFailure(Exception e) {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() ->
                            Toast.makeText(requireContext(), "Could not save folder: " + e.getMessage(), Toast.LENGTH_LONG).show());
                }
            }
        });
    }

    private void importTracksIntoFolder(Folder folder, List<SpotifyWebApiClient.SpotifyTrack> tracks) {
        int created = 0;
        int skippedDuplicates = 0;
        // Tracks already queued this same import count as "existing" too, so
        // a playlist with the same song listed twice doesn't create two presets.
        List<SelectedEqualizer> combinedExisting = new ArrayList<>(presets);

        for (SpotifyWebApiClient.SpotifyTrack track : tracks) {
            if (isDuplicatePreset(track.name, track.artist, 0, combinedExisting)) {
                skippedDuplicates++;
                continue;
            }

            List<Integer> bandIds = new ArrayList<>();
            List<Integer> initialLevels = new ArrayList<>();
            for (int i = 0; i < NUM_BANDS; i++) {
                bandIds.add(i);
                initialLevels.add(0);
            }

            SelectedEqualizer eq = new SelectedEqualizer(track.name, track.artist, 0, bandIds, initialLevels);
            eq.setFolderId(folder.getId());
            combinedExisting.add(eq);

            dataHandler.saveEqualizer(eq, new EqualizerDataHandler.OperationCallback() {
                @Override public void onSuccess() {}
                @Override public void onFailure(Exception e) {
                    Log.e("PLAYLIST_IMPORT", "Failed to save imported preset: " + track.name, e);
                }
            });
            created++;
        }

        Toast.makeText(requireContext(),
                "Imported " + created + " song" + (created == 1 ? "" : "s")
                        + (skippedDuplicates > 0 ? " (" + skippedDuplicates + " already existed)" : "")
                        + " into \"" + folder.getName() + "\"",
                Toast.LENGTH_LONG).show();
    }

    /** Same song+artist (case-insensitive) for type 0, or same name for type 1 (genre). */
    private boolean isDuplicatePreset(String name, String artist, int type, List<SelectedEqualizer> existing) {
        for (SelectedEqualizer eq : existing) {
            if (eq.getType() != type) continue;
            boolean nameMatches = eq.getName() != null && eq.getName().equalsIgnoreCase(name);
            if (!nameMatches) continue;

            if (type == 0) {
                String existingArtist = eq.getArtist() == null ? "" : eq.getArtist();
                String newArtist = artist == null ? "" : artist;
                if (existingArtist.equalsIgnoreCase(newArtist)) return true;
            } else {
                return true;
            }
        }
        return false;
    }

    /**
     * Renders the drawer: every folder, with that folder's presets inserted
     * indented directly below it if it's the currently expanded one, followed
     * by top-level presets (not in any folder).
     */
    private void updateDrawerMenu() {
        android.view.Menu menu = navView.getMenu();
        menu.clear(); // Safe clean clearing operation execution path
        menuItemTargets.clear();

        int groupId = 2;
        menu.setGroupCheckable(groupId, false, false);

        for (Folder folder : folders) {
            int folderMenuId = stableMenuItemId(folder.getId());
            android.view.MenuItem folderItem = menu.add(groupId, folderMenuId, android.view.Menu.NONE, folder.getName())
                    .setIcon(android.R.drawable.ic_menu_agenda);

            // Give folder items the same action-view structure every other
            // item has, just with the delete button hidden - not wiring up
            // folder deletion yet (still an open question what happens to the
            // presets inside), but keeping every row structurally identical
            // avoids relying on NavigationView's item recycling handling a
            // mix of "has an action view" / "doesn't" correctly.
            folderItem.setActionView(R.layout.menu_delete_action);
            View folderActionView = folderItem.getActionView();
            if (folderActionView != null) {
                View folderDeleteBtn = folderActionView.findViewById(R.id.btn_delete_preset);
                if (folderDeleteBtn != null) folderDeleteBtn.setVisibility(View.INVISIBLE);
            }

            menuItemTargets.put(folderMenuId, folder);

            if (!folder.getId().equals(expandedFolderId)) continue;

            for (SelectedEqualizer eq : presets) {
                if (!folder.getId().equals(eq.getFolderId())) continue;

                int presetMenuId = stableMenuItemId(eq.getId());
                android.view.MenuItem item = menu.add(groupId, presetMenuId, android.view.Menu.NONE, "     " + eq.getDisplayName())
                        .setIcon(android.R.drawable.ic_media_next);

                item.setActionView(R.layout.menu_delete_action);
                View deleteBtn = item.getActionView().findViewById(R.id.btn_delete_preset);
                deleteBtn.setOnClickListener(v -> showDeleteConfirmationDialog(eq));

                menuItemTargets.put(presetMenuId, eq);
            }
        }

        for (SelectedEqualizer eq : presets) {
            if (eq.getFolderId() != null) continue;

            int presetMenuId = stableMenuItemId(eq.getId());
            android.view.MenuItem item = menu.add(groupId, presetMenuId, android.view.Menu.NONE, eq.getDisplayName())
                    .setIcon(android.R.drawable.ic_media_next);

            item.setActionView(R.layout.menu_delete_action);

            View actionView = item.getActionView();
            View deleteBtn = actionView.findViewById(R.id.btn_delete_preset);

            deleteBtn.setOnClickListener(v -> {
                DrawerLayout drawer = getView().findViewById(R.id.eq_drawer);
                if(drawer != null) drawer.closeDrawer(GravityCompat.START);

                showDeleteConfirmationDialog(eq);
            });

            menuItemTargets.put(presetMenuId, eq);
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
            DynamicsProcessing.Config config = new DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    /* channelCount= */ 2,
                    /* preEqInUse= */ true,
                    /* preEqBandCount= */ NUM_BANDS,
                    /* mbcInUse= */ false,
                    /* mbcBandCount= */ 0,
                    /* postEqInUse= */ false,
                    /* postEqBandCount= */ 0,
                    /* limiterInUse= */ false)
                    .build();

            systemEq = new DynamicsProcessing(/* priority= */ 0, audioSessionId, config);
            systemEq.setEnabled(true);

            // The Builder above seeds every band with its own default frequency
            // spacing; overwrite each one now with our 30 Hz-8000 Hz layout so
            // the initial engine state goes through the exact same call every
            // later live update uses.
            for (int b = 0; b < NUM_BANDS; b++) {
                systemEq.setPreEqBandAllChannelsTo(b,
                        new DynamicsProcessing.EqBand(true, BAND_FREQUENCIES_HZ[b], 0f));
            }

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


        for (int band = 0; band < NUM_BANDS; band++) {
            final int finalBand = band;

            View bandView = LayoutInflater.from(requireContext()).inflate(R.layout.equalizer_band_item, bandsContainer, false);


            if (bandView instanceof ViewGroup) {
                ((ViewGroup) bandView).setClipChildren(false);
                ((ViewGroup) bandView).setClipToPadding(false);
            }

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.0f);
            bandView.setLayoutParams(params);

            VerticalSeekBar sb = bandView.findViewById(R.id.eq_band_seekbar);
            TextView label = bandView.findViewById(R.id.eq_band_label);

            sb.setMax(SPAN);
            int currentLevel = gainDbToLevel(systemEq.getPreEqBandByChannelIndex(0, finalBand).getGain());
            sb.setProgress(currentLevel - MIN_LEVEL);

            if (label != null) {
                label.setText(formatFrequencyLabel(BAND_FREQUENCIES_HZ[finalBand]));
            }



            sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                    // Updates the live audio engine + in-memory model
                    // immediately. The Firebase write is debounced below: every
                    // call here resets a short timer, so the write only fires
                    // once movement pauses. This is the primary save path -
                    // onStopTrackingTouch isn't reliably called by every seekbar
                    // implementation, so saving doesn't depend on it firing.
                    int targetLevel = MIN_LEVEL + progress;

                    if (systemEq != null) {
                        systemEq.setPreEqBandAllChannelsTo(finalBand,
                                new DynamicsProcessing.EqBand(true, BAND_FREQUENCIES_HZ[finalBand], levelToGainDb(targetLevel)));

                        if (currentEq != null && currentEq.getBandLevels() != null
                                && finalBand < currentEq.getBandLevels().size()) {
                            currentEq.getBandLevels().set(finalBand, targetLevel);
                        }
                    }

                    if (sharedTooltip != null) {
                        sharedTooltip.setText(formatLevelAsDb(targetLevel));
                    }

                    if (currentEq != null && dataHandler != null) {
                        saveHandler.removeCallbacks(pendingBandLevelSave);
                        saveHandler.postDelayed(pendingBandLevelSave, BAND_LEVEL_SAVE_DEBOUNCE_MS);
                    }
                }
                @Override public void onStartTrackingTouch(SeekBar seekBar) {
                    if (sharedTooltip != null) {
                        sharedTooltip.setText(formatLevelAsDb(MIN_LEVEL + seekBar.getProgress()));
                        sharedTooltip.setVisibility(View.VISIBLE);
                        sharedTooltip.post(() -> positionSharedTooltip(bandView));
                    }
                }
                @Override public void onStopTrackingTouch(SeekBar seekBar) {
                    // If this DOES fire, save immediately instead of waiting out
                    // the debounce timer scheduled above.
                    saveHandler.removeCallbacks(pendingBandLevelSave);
                    persistCurrentBandLevels();

                    if (sharedTooltip != null) {
                        sharedTooltip.setVisibility(View.INVISIBLE);
                    }
                }
            });

            bandsContainer.addView(bandView);
        }
    }

    /**
     * Moves the single shared dB tooltip to sit beside whichever band is
     * currently being dragged - to the right of it, except for the leftmost
     * band, which shows it on the left instead. Doesn't track the thumb's
     * vertical position; it's centered on the band's height once per drag,
     * since the user's own finger covers the thumb while dragging anyway.
     */
    private void positionSharedTooltip(View bandView) {
        if (sharedTooltip == null || bandsContainer == null) return;

        boolean isLeftmostBand = bandsContainer.indexOfChild(bandView) == 0;
        float bandLeftInGraph = bandsContainer.getX() + bandView.getX();

        float targetX = isLeftmostBand
                ? bandLeftInGraph - sharedTooltip.getWidth()
                : bandLeftInGraph + bandView.getWidth();
        float targetY = bandsContainer.getY() + bandView.getY()
                + (bandView.getHeight() - sharedTooltip.getHeight()) / 2f;

        sharedTooltip.setX(targetX);
        sharedTooltip.setY(targetY);
    }

    /**
     * Saves the full set of band levels for the current preset, read directly
     * from systemEq (the live audio engine) rather than trusting the in-memory
     * currentEq.bandLevels list to have stayed perfectly in sync. systemEq
     * always holds exactly NUM_BANDS valid bands, so this can never hand
     * Firebase a null or short-length list.
     */
    private void persistCurrentBandLevels() {
        if (currentEq == null || dataHandler == null || systemEq == null) return;

        List<Integer> freshLevels = new ArrayList<>(NUM_BANDS);
        for (int i = 0; i < NUM_BANDS; i++) {
            float gainDb = systemEq.getPreEqBandByChannelIndex(0, i).getGain();
            freshLevels.add(gainDbToLevel(gainDb));
        }
        currentEq.setBandLevels(freshLevels);

        String presetId = currentEq.getId();
        dataHandler.updateBandLevels(presetId, freshLevels, new EqualizerDataHandler.OperationCallback() {
            @Override
            public void onSuccess() {
                Log.d("EQ_SAVE", "Band levels saved for " + presetId);
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("EQ_SAVE", "Failed to save band levels", e);
            }
        });
    }

    private static int gainDbToLevel(float gainDb) {
        return Math.round(gainDb * 10f);
    }

    private static float levelToGainDb(int level) {
        return level / 10f;
    }

    private static String formatLevelAsDb(int level) {
        return String.format(Locale.US, "%.1f dB", levelToGainDb(level));
    }

    private static String formatFrequencyLabel(float freqHz) {
        if (freqHz >= 1000f) {
            return String.format(Locale.US, "%.1f kHz", freqHz / 1000f);
        }
        return Math.round(freqHz) + " Hz";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        saveHandler.removeCallbacks(pendingBandLevelSave);
        if (dataHandler != null) {
            dataHandler.stopListening();
        }
        // To keep the equalizer working while in settings, we DO NOT release it here.
        // It will be released when the fragment is actually destroyed (onDestroy) or if we implement
        // release logic in the Activity.
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (systemEq != null) {
            systemEq.release();
            systemEq = null;
        }
    }
}