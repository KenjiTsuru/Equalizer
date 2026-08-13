package com.example.autoeq;

import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.media.audiofx.DynamicsProcessing;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;

import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class EqualizerEditorFragment extends Fragment {

    // EQ shape: 12 bands, log-spaced 30 Hz-8000 Hz, +/-12 dB range. Stored and
    // passed around as tenths-of-a-dB integers (e.g. 35 = 3.5 dB) so
    // SelectedEqualizer's List<Integer> and the SeekBar's integer progress
    // don't need to change shape just because DynamicsProcessing's native
    // unit is a float dB rather than the old Equalizer's millibel short.
    private static final float MIN_GAIN_DB = -12f;
    private static final float MAX_GAIN_DB = 12f;
    private static final int MIN_LEVEL = Math.round(MIN_GAIN_DB * 10);
    private static final int MAX_LEVEL = Math.round(MAX_GAIN_DB * 10);
    private static final int SPAN = MAX_LEVEL - MIN_LEVEL;

    // Owned by SpotifyMonitorService, not this Fragment - both systemEq and
    // spotifyService.getSystemEq() are the same object once bound. See
    // onServiceConnected below for why ownership moved out of the Fragment.
    private SpotifyMonitorService spotifyService;
    private boolean serviceBound = false;
    private final android.content.ServiceConnection serviceConnection = new android.content.ServiceConnection() {
        @Override
        public void onServiceConnected(android.content.ComponentName name, android.os.IBinder binder) {
            spotifyService = ((SpotifyMonitorService.LocalBinder) binder).getService();
            serviceBound = true;
            systemEq = spotifyService.getSystemEq();
            if (powerSwitch != null) {
                powerSwitch.setEnabled(systemEq != null);
                if (systemEq != null) powerSwitch.setChecked(systemEq.getEnabled());
            }
            spotifyService.setStateListener(() -> {
                if (!isAdded()) return;
                requireActivity().runOnUiThread(EqualizerEditorFragment.this::onServiceStateChanged);
            });
            if (currentEq == null && spotifyService.getCurrentEq() != null) {
                onServiceStateChanged();
            }
            if (eqUiContainer != null && eqUiContainer.getVisibility() == View.VISIBLE) {
                buildBandUiFromSystemEqualizer();
            }
        }

        @Override
        public void onServiceDisconnected(android.content.ComponentName name) {
            serviceBound = false;
            spotifyService = null;
        }
    };

    private DynamicsProcessing systemEq;
    private SelectedEqualizer currentEq;
    private NavigationView navView;
    private LinearLayout bandsContainer;
    private EqualizerCurveView curveView;

    // Firebase Data Handler reference replaces local indexing pools
    private EqualizerDataHandler dataHandler;
    private List<SelectedEqualizer> presets = new ArrayList<>();
    private List<Folder> folders = new ArrayList<>();

    // Drawer state: id of the one folder currently expanded (its presets show
    // indented directly below it), or null if none are expanded.
    private String expandedFolderId = null;

    // Selection mode: long-pressing a row enters it, showing a checkbox on
    // every row and swapping the + button for delete/move/exit buttons.
    // Regular taps then toggle selection instead of their normal action
    // until exited.
    private boolean selectionMode = false;
    private final Set<String> selectedPresetIds = new HashSet<>();
    private final Set<String> selectedFolderIds = new HashSet<>();

    private SpotifyWebApiClient spotifyWebApiClient;
    private LastFmApiClient lastFmApiClient;
    private DiscogsApiClient discogsApiClient;

    // Registered as a field initializer (not inside a click handler) since
    // AndroidX requires every ActivityResultLauncher to be registered before
    // the Fragment reaches STARTED - doing it lazily on first tap would throw.
    private final ActivityResultLauncher<String[]> localFilePickerLauncher =
            registerForActivityResult(new ActivityResultContracts.OpenMultipleDocuments(), this::onLocalFilesPicked);

    private View emptyStateText;
    private View eqUiContainer;
    private MaterialToolbar toolbar;
    private TextView presetNameText;
    private TextView sharedTooltip;
    private SwitchCompat powerSwitch;
    private EditText searchBar;
    private View btnCreateEq;
    private View btnDeleteSelected;
    private View btnMoveSelected;
    private View btnExitSelection;

    // Shown during playlist import and multi-select delete - both do a
    // network round trip that can take a few seconds for a large playlist,
    // and previously gave no indication anything was happening while the
    // (now-fixed) N-writes-in-a-loop bug froze the UI. Kept around and
    // reused rather than rebuilt per call.
    private AlertDialog progressDialog;
    private ProgressBar progressDialogBar;
    private TextView progressDialogText;

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
        curveView = view.findViewById(R.id.eq_curve_view);
        presetNameText = view.findViewById(R.id.eq_preset_name);
        presetNameText.setOnClickListener(v -> showSongOptionsDialog());
        sharedTooltip = view.findViewById(R.id.eq_shared_tooltip);

        toolbar.setNavigationIcon(R.drawable.ic_hamburger_menu);
        toolbar.setNavigationOnClickListener(v -> drawerLayout.openDrawer(GravityCompat.START));

        View headerView = navView.getHeaderView(0);
        btnCreateEq = headerView.findViewById(R.id.btn_create_eq);
        btnDeleteSelected = headerView.findViewById(R.id.btn_delete_selected);
        btnMoveSelected = headerView.findViewById(R.id.btn_move_selected);
        btnExitSelection = headerView.findViewById(R.id.btn_exit_selection);
        searchBar = headerView.findViewById(R.id.drawer_search_bar);

        if (searchBar != null) {
            searchBar.addTextChangedListener(new android.text.TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                    refreshDrawerList();
                }
                @Override
                public void afterTextChanged(android.text.Editable s) {}
            });
        }

        if (btnCreateEq != null) {
            btnCreateEq.setOnClickListener(v -> {
                drawerLayout.closeDrawer(GravityCompat.START);
                showCreateChooserDialog();
            });
        }

        if (btnDeleteSelected != null) {
            btnDeleteSelected.setOnClickListener(v -> showDeleteSelectedConfirmation());
        }

        if (btnMoveSelected != null) {
            btnMoveSelected.setOnClickListener(v -> showMoveToFolderDialog());
        }

        if (btnExitSelection != null) {
            btnExitSelection.setOnClickListener(v -> {
                setSelectionMode(false);
                refreshDrawerList();
            });
        }

        dataHandler = new EqualizerDataHandler();

        // Every row now handles its own tap/long-press directly (see
        // buildDrawerRowView) via a full-width custom view, so there's
        // nothing left for NavigationView's own item-selected dispatch to do.

        bindToSpotifyService();

        // Global on/off for the system equalizer effect - not tied to any preset.
        // Actual enabled/checked state gets synced once the service binding
        // completes (see serviceConnection above) - systemEq is null until then.
        powerSwitch = view.findViewById(R.id.eq_power_switch);
        if (powerSwitch != null) {
            powerSwitch.setEnabled(false);
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

                refreshDrawerList();

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
                refreshDrawerList();
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

    /** Re-renders whichever view is currently showing (search results or the normal drawer). */
    private void refreshDrawerList() {
        String query = searchBar != null ? searchBar.getText().toString() : "";
        if (query.isEmpty()) {
            updateDrawerMenu();
        } else {
            filterDrawerMenu(query);
        }
    }

    /**
     * Same idea as refreshDrawerList, but deferred a frame. NavigationView
     * suspends its own menu-refresh logic while still inside handling a
     * click - rebuilding synchronously from within a row's own click/
     * long-click listener updates the underlying data correctly, but the
     * visible list silently won't reflect it until something else triggers a
     * redraw. Used for anything triggered directly from a row tap.
     */
    private void scheduleDrawerRefresh() {
        navView.post(this::refreshDrawerList);
    }

    /**
     * Renders the search-filtered view: presets only, matched by name/artist,
     * searched across ALL presets regardless of folder or which folder is
     * currently open. Folders themselves are never matched or shown here.
     */
    private void filterDrawerMenu(String query) {
        android.view.Menu menu = navView.getMenu();
        menu.clear();

        int groupId = 2;
        int dynamicId = 2000;

        for (SelectedEqualizer eq : presets) {
            if (!eq.getDisplayName().toLowerCase().contains(query.toLowerCase())) continue;

            android.view.MenuItem item = menu.add(groupId, dynamicId++, android.view.Menu.NONE, "");
            item.setActionView(buildDrawerRowView(
                    eq.getDisplayName(),
                    presetFallbackIcon(eq),
                    resolveAlbumArtUrl(eq),
                    selectedPresetIds.contains(eq.getId()),
                    v -> onPresetRowClicked(eq),
                    v -> onPresetRowLongClicked(eq)
            ));
        }
    }

    /**
     * Renders the drawer: every folder, with that folder's presets inserted
     * indented directly below it if it's the currently expanded one, followed
     * by top-level presets (not in any folder). In selection mode every row
     * shows a checkbox reflecting whether it's currently selected.
     */
    private void updateDrawerMenu() {
        android.view.Menu menu = navView.getMenu();
        menu.clear();

        int groupId = 2;
        int dynamicId = 2000;

        for (Folder folder : folders) {
            boolean expanded = folder.getId().equals(expandedFolderId);

            android.view.MenuItem folderItem = menu.add(groupId, dynamicId++, android.view.Menu.NONE, "");
            View folderRow = buildDrawerRowView(
                    folder.getName(),
                    R.drawable.ic_chevron_right,
                    expanded ? 90f : 0f,
                    selectedFolderIds.contains(folder.getId()),
                    v -> onFolderRowClicked(folder),
                    v -> onFolderRowLongClicked(folder)
            );
            bindFolderRefreshAction(folderRow, folder);
            folderItem.setActionView(folderRow);

            if (!expanded) continue;

            for (SelectedEqualizer eq : presets) {
                if (!folder.getId().equals(eq.getFolderId())) continue;

                android.view.MenuItem presetItem = menu.add(groupId, dynamicId++, android.view.Menu.NONE, "");
                presetItem.setActionView(buildDrawerRowView(
                        "     " + eq.getDisplayName(),
                        presetFallbackIcon(eq),
                        resolveAlbumArtUrl(eq),
                        selectedPresetIds.contains(eq.getId()),
                        v -> onPresetRowClicked(eq),
                        v -> onPresetRowLongClicked(eq)
                ));
            }
        }

        for (SelectedEqualizer eq : presets) {
            if (eq.getFolderId() != null) continue;

            android.view.MenuItem item = menu.add(groupId, dynamicId++, android.view.Menu.NONE, "");
            item.setActionView(buildDrawerRowView(
                    eq.getDisplayName(),
                    presetFallbackIcon(eq),
                    resolveAlbumArtUrl(eq),
                    selectedPresetIds.contains(eq.getId()),
                    v -> onPresetRowClicked(eq),
                    v -> onPresetRowLongClicked(eq)
            ));
        }
    }

    /**
     * Builds one full-width drawer row as a MenuItem's action view. This
     * entirely replaces NavigationView's default item rendering (and the old
     * small delete-button action view) with a view that handles its own tap
     * and long-press directly - NavigationView's Menu API has no long-click
     * callback of its own, so a custom view is the only way to detect one.
     */
    private View buildDrawerRowView(String title, int iconRes, boolean checked,
                                    View.OnClickListener onClick, View.OnLongClickListener onLongClick) {
        return buildDrawerRowView(title, iconRes, null, 0f, checked, onClick, onLongClick);
    }

    /**
     * Same as above, plus a rotation for the row icon - used for the folder
     * disclosure chevron, which points right when collapsed and rotates to
     * point down once expanded, rather than swapping between two drawables.
     */
    private View buildDrawerRowView(String title, int iconRes, float iconRotationDegrees, boolean checked,
                                    View.OnClickListener onClick, View.OnLongClickListener onLongClick) {
        return buildDrawerRowView(title, iconRes, null, iconRotationDegrees, checked, onClick, onLongClick);
    }

    /**
     * Same as above, plus a real album art URL - used for song presets
     * imported from a Spotify playlist (see SpotifyWebApiClient.SpotifyTrack
     * and importTracksIntoFolder). Null falls back to fallbackIconRes, same
     * as every other preset row (manually-created presets never have art).
     */
    private View buildDrawerRowView(String title, int fallbackIconRes, String albumArtUrl, boolean checked,
                                    View.OnClickListener onClick, View.OnLongClickListener onLongClick) {
        return buildDrawerRowView(title, fallbackIconRes, albumArtUrl, 0f, checked, onClick, onLongClick);
    }

    /**
     * Builds one full-width drawer row as a MenuItem's action view. This
     * entirely replaces NavigationView's default item rendering (and the old
     * small delete-button action view) with a view that handles its own tap
     * and long-press directly - NavigationView's Menu API has no long-click
     * callback of its own, so a custom view is the only way to detect one.
     *
     * When albumArtUrl is present, Glide loads it into the icon instead of
     * fallbackIconRes - it decodes straight to the ImageView's fixed 24dp
     * size (never holds a full-res bitmap in memory) and disk-caches the
     * result, so repeat renders of the same row are free.
     */
    private View buildDrawerRowView(String title, int fallbackIconRes, String albumArtUrl, float iconRotationDegrees,
                                    boolean checked, View.OnClickListener onClick, View.OnLongClickListener onLongClick) {
        View row = LayoutInflater.from(requireContext()).inflate(R.layout.menu_preset_row, null, false);

        ImageView icon = row.findViewById(R.id.row_icon);
        TextView titleView = row.findViewById(R.id.row_title);
        CheckBox checkbox = row.findViewById(R.id.row_checkbox);

        // menu_preset_row.xml applies app:tint at inflate time - captured
        // here, before it's ever overwritten, so it can be restored for
        // fallback icons after real (untinted) album art has been shown.
        ColorStateList defaultIconTint = icon.getImageTintList();

        if (albumArtUrl != null && !albumArtUrl.isEmpty()) {
            // Real artwork shouldn't be forced into the monochrome icon tint.
            icon.setImageTintList(null);
            Glide.with(icon)
                    .load(albumArtUrl)
                    .placeholder(fallbackIconRes)
                    .error(fallbackIconRes)
                    .centerCrop()
                    .into(icon);
        } else {
            icon.setImageTintList(defaultIconTint);
            icon.setImageResource(fallbackIconRes);
        }
        icon.setRotation(iconRotationDegrees);
        titleView.setText(title);
        checkbox.setVisibility(selectionMode ? View.VISIBLE : View.GONE);
        checkbox.setChecked(checked);

        row.setOnClickListener(onClick);
        row.setOnLongClickListener(onLongClick);

        return row;
    }

    /** Follows linkedPresetId to find the real art owner, same as band levels. Null if eq has none (e.g. manually created). */
    private String resolveAlbumArtUrl(SelectedEqualizer eq) {
        SelectedEqualizer dataSource = resolveDataSource(eq);
        return dataSource != null ? dataSource.getAlbumArtUrl() : null;
    }

    /**
     * A local-file import (see importLocalTracks) never has album art, so it
     * always falls into buildDrawerRowView's fallback-icon branch - this is
     * what makes it visually distinct from a Spotify-imported or manually
     * created preset in the drawer, without needing a separate badge/overlay.
     */
    private int presetFallbackIcon(SelectedEqualizer eq) {
        SelectedEqualizer dataSource = resolveDataSource(eq);
        String source = dataSource != null ? dataSource.getSource() : null;
        return "local".equals(source) ? R.drawable.ic_local_file : android.R.drawable.ic_media_next;
    }

    /**
     * Spotify-linked folders only: wires up the refresh icon added to
     * menu_preset_row.xml - a manual "check now" the user can hit any time.
     * Purely manual, no automatic background checking - that was tried and
     * removed (see the "getting rid of the automatic playlist refreshing"
     * conversation this came out of): Spotify's own snapshot_id can lag
     * behind a playlist's real contents, which made the background version
     * unreliable both for auto-applying changes and for deciding when to
     * light this icon up, and the manual tap alone was already proven to
     * work correctly on its own.
     */
    private void bindFolderRefreshAction(View row, Folder folder) {
        ImageView refreshIcon = row.findViewById(R.id.row_refresh_icon);
        if (refreshIcon == null || folder.getSpotifyPlaylistId() == null) return;

        refreshIcon.setVisibility(View.VISIBLE);
        refreshIcon.setOnClickListener(v -> onFolderRefreshClicked(folder));
    }

    /** Shows (or updates, if already showing) a non-cancelable "please wait" dialog with an indeterminate spinner. */
    private void showProgressDialog(String message) {
        if (!isAdded()) return;
        if (progressDialog == null) {
            View view = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_progress, null, false);
            progressDialogBar = view.findViewById(R.id.progress_dialog_bar);
            progressDialogText = view.findViewById(R.id.progress_dialog_text);
            progressDialog = new AlertDialog.Builder(requireContext())
                    .setView(view)
                    .setCancelable(false)
                    .create();
        }
        progressDialogBar.setIndeterminate(true);
        progressDialogText.setText(message);
        if (!progressDialog.isShowing()) progressDialog.show();
    }

    /** Switches the dialog to a determinate bar reflecting fetched/total - used while paginating a playlist, where the total is known up front. */
    private void updateProgressDialog(String message, int fetched, int total) {
        if (progressDialog == null || !progressDialog.isShowing()) return;
        if (total > 0) {
            progressDialogBar.setIndeterminate(false);
            progressDialogBar.setMax(total);
            progressDialogBar.setProgress(fetched);
        } else {
            progressDialogBar.setIndeterminate(true);
        }
        progressDialogText.setText(message);
    }

    private void dismissProgressDialog() {
        if (progressDialog != null && progressDialog.isShowing()) progressDialog.dismiss();
    }

    private void onFolderRowClicked(Folder folder) {
        if (selectionMode) {
            toggleFolderSelection(folder.getId());
            return;
        }
        expandedFolderId = folder.getId().equals(expandedFolderId) ? null : folder.getId();
        scheduleDrawerRefresh();
    }

    private boolean onFolderRowLongClicked(Folder folder) {
        if (!selectionMode) setSelectionMode(true);
        toggleFolderSelection(folder.getId());
        return true;
    }

    private void toggleFolderSelection(String folderId) {
        if (!selectedFolderIds.remove(folderId)) {
            selectedFolderIds.add(folderId);
        }
        scheduleDrawerRefresh();
    }

    private void onPresetRowClicked(SelectedEqualizer eq) {
        if (selectionMode) {
            togglePresetSelection(eq.getId());
            return;
        }
        applySelectedPreset(eq);
        View root = getView();
        DrawerLayout drawer = root != null ? root.findViewById(R.id.eq_drawer) : null;
        if (drawer != null) drawer.closeDrawer(GravityCompat.START);
    }

    private boolean onPresetRowLongClicked(SelectedEqualizer eq) {
        if (!selectionMode) setSelectionMode(true);
        togglePresetSelection(eq.getId());
        return true;
    }

    private void togglePresetSelection(String presetId) {
        if (!selectedPresetIds.remove(presetId)) {
            selectedPresetIds.add(presetId);
        }
        scheduleDrawerRefresh();
    }

    /** Toggles between the + button and the delete/move/exit buttons. Doesn't refresh the list itself - callers do that once all state changes are settled. */
    private void setSelectionMode(boolean enabled) {
        selectionMode = enabled;
        if (!enabled) {
            selectedPresetIds.clear();
            selectedFolderIds.clear();
        }
        if (btnCreateEq != null) btnCreateEq.setVisibility(enabled ? View.GONE : View.VISIBLE);
        if (btnDeleteSelected != null) btnDeleteSelected.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (btnMoveSelected != null) btnMoveSelected.setVisibility(enabled ? View.VISIBLE : View.GONE);
        if (btnExitSelection != null) btnExitSelection.setVisibility(enabled ? View.VISIBLE : View.GONE);
    }

    private void showDeleteSelectedConfirmation() {
        int presetCount = selectedPresetIds.size();
        int folderCount = selectedFolderIds.size();
        if (presetCount == 0 && folderCount == 0) return;

        StringBuilder message = new StringBuilder("Delete ");
        if (folderCount > 0) {
            message.append(folderCount).append(folderCount == 1 ? " folder" : " folders");
            message.append(" (and everything inside ").append(folderCount == 1 ? "it" : "them").append(")");
        }
        if (presetCount > 0) {
            if (folderCount > 0) message.append(" and ");
            message.append(presetCount).append(presetCount == 1 ? " preset" : " presets");
        }
        message.append("?");

        new AlertDialog.Builder(requireContext())
                .setTitle("Delete selected")
                .setMessage(message.toString())
                .setPositiveButton("Delete", (dialog, which) -> deleteSelected())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Deletes everything selected in two batched calls (all presets in one
     * update, all folders in another) instead of one Firebase write per
     * item. Deleting one at a time was what froze the UI on a multi-select
     * delete: every removeValue() re-fires the whole-list listener, and each
     * fire rebuilds the entire drawer menu from scratch - for N items that's
     * N full rebuilds instead of two (one per batch).
     */
    private void deleteSelected() {
        if (dataHandler == null) return;

        List<String> folderIdsToDelete = new ArrayList<>(selectedFolderIds);
        List<String> presetIdsToDelete = new ArrayList<>();
        for (SelectedEqualizer eq : presets) {
            // Deleting a folder deletes every preset inside it too, not just
            // the folder itself - covered here by also including any preset
            // whose folder is selected, whether or not the preset itself is.
            boolean inSelectedFolder = eq.getFolderId() != null && selectedFolderIds.contains(eq.getFolderId());
            if (selectedPresetIds.contains(eq.getId()) || inSelectedFolder) {
                presetIdsToDelete.add(eq.getId());
            }
        }

        int pendingBatches = (presetIdsToDelete.isEmpty() ? 0 : 1) + (folderIdsToDelete.isEmpty() ? 0 : 1);
        if (pendingBatches == 0) {
            setSelectionMode(false);
            refreshDrawerList();
            return;
        }

        showProgressDialog("Deleting...");
        int[] remaining = {pendingBatches};
        Runnable onBatchFinished = () -> {
            if (--remaining[0] > 0 || !isAdded()) return;
            dismissProgressDialog();
            Toast.makeText(requireContext(), "Deleted", Toast.LENGTH_SHORT).show();
            setSelectionMode(false);
            refreshDrawerList();
        };

        if (!presetIdsToDelete.isEmpty()) {
            dataHandler.deleteEqualizers(presetIdsToDelete, new EqualizerDataHandler.OperationCallback() {
                @Override public void onSuccess() { onBatchFinished.run(); }
                @Override public void onFailure(Exception e) {
                    Log.e("PRESET_DELETE", "Failed to delete presets", e);
                    onBatchFinished.run();
                }
            });
        }
        if (!folderIdsToDelete.isEmpty()) {
            dataHandler.deleteFolders(folderIdsToDelete, new EqualizerDataHandler.OperationCallback() {
                @Override public void onSuccess() { onBatchFinished.run(); }
                @Override public void onFailure(Exception e) {
                    Log.e("FOLDER_DELETE", "Failed to delete folders", e);
                    onBatchFinished.run();
                }
            });
        }
    }

    private void showMoveToFolderDialog() {
        if (selectedPresetIds.isEmpty()) {
            Toast.makeText(requireContext(), "No presets selected", Toast.LENGTH_SHORT).show();
            return;
        }
        if (folders.isEmpty()) {
            Toast.makeText(requireContext(), "No folders yet - create one first", Toast.LENGTH_SHORT).show();
            return;
        }

        String[] names = new String[folders.size()];
        for (int i = 0; i < folders.size(); i++) {
            names[i] = folders.get(i).getName();
        }

        new AlertDialog.Builder(requireContext())
                .setTitle("Move to folder")
                .setItems(names, (dialog, which) -> moveSelectedPresetsToFolder(folders.get(which)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void moveSelectedPresetsToFolder(Folder folder) {
        if (dataHandler == null) return;

        for (SelectedEqualizer eq : presets) {
            if (!selectedPresetIds.contains(eq.getId())) continue;

            eq.setFolderId(folder.getId());
            dataHandler.updateFolderAssignment(eq.getId(), folder.getId(), new EqualizerDataHandler.OperationCallback() {
                @Override public void onSuccess() {}
                @Override public void onFailure(Exception e) {
                    Log.e("MOVE_PRESET", "Failed to move preset: " + eq.getName(), e);
                }
            });
        }

        Toast.makeText(requireContext(), "Moved to \"" + folder.getName() + "\"", Toast.LENGTH_SHORT).show();
        setSelectionMode(false);
        refreshDrawerList();
    }

    private void applySelectedPreset(SelectedEqualizer eq) {
        currentEq = eq;
        updateCurrentEqDisplay();

        SelectedEqualizer dataSource = resolveDataSource(eq);
        if (serviceBound && spotifyService != null && dataSource != null) {
            spotifyService.applyPreset(dataSource);
            if (dataSource != eq) {
                eq.setBandLevels(dataSource.getBandLevels());
            }
        }

        buildBandUiFromSystemEqualizer();
    }

    private void bindToSpotifyService() {
        android.content.Intent intent = new android.content.Intent(requireContext(), SpotifyMonitorService.class);
        requireContext().bindService(intent, serviceConnection, android.content.Context.BIND_AUTO_CREATE);
    }

    /**
     * Called (on the main thread) whenever SpotifyMonitorService's state
     * changes on its own - i.e. an auto-switch from a Spotify track change,
     * not something this Fragment initiated. Re-reads the service's current
     * state and refreshes the UI to match, same as a manual selection would.
     */
    private void onServiceStateChanged() {
        if (spotifyService == null) return;
        currentEq = spotifyService.getCurrentEq();
        updateCurrentEqDisplay();
        if (powerSwitch != null && systemEq != null) {
            powerSwitch.setChecked(systemEq.getEnabled());
        }
        if (systemEq != null && eqUiContainer != null && eqUiContainer.getVisibility() == View.VISIBLE) {
            buildBandUiFromSystemEqualizer();
        }
    }

    /**
     * Follows linkedPresetId to find the preset that actually owns the
     * bandLevels for eq. A preset with no link owns its own data and
     * resolves to itself. Falls back to eq itself if the link target can't
     * be found (e.g. the original was deleted), rather than showing nothing.
     */
    private SelectedEqualizer resolveDataSource(SelectedEqualizer eq) {
        if (eq == null || eq.getLinkedPresetId() == null) return eq;
        for (SelectedEqualizer candidate : presets) {
            if (eq.getLinkedPresetId().equals(candidate.getId())) {
                return candidate;
            }
        }
        return eq;
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
        String[] options = {"New Preset", "New Folder", "Import Playlist", "Import from Files"};
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_AutoEQ_Dialog)
                .setTitle("Add New")
                .setItems(options, (dialog, which) -> {
                    if (which == 0) showCreateEqualizerDialog();
                    else if (which == 1) showCreateFolderDialog();
                    else if (which == 2) showImportPlaylistDialog();
                    else localFilePickerLauncher.launch(new String[]{"audio/*"});
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showCreateEqualizerDialog() {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_AutoEQ_Dialog);
        // Views must be built from the builder's themed context, not the fragment's -
        // otherwise the EditText/Spinner keep the default light styling (dark text
        // on a now-dark background) even though the dialog chrome around them is themed.
        Context dialogContext = builder.getContext();

        LinearLayout layout = new LinearLayout(dialogContext);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 10);

        final EditText songNameInput = new EditText(dialogContext);
        songNameInput.setHint("Song Name");
        layout.addView(songNameInput);

        final EditText artistNameInput = new EditText(dialogContext);
        artistNameInput.setHint("Artist Name");
        layout.addView(artistNameInput);

        final Spinner typeSpinner = new Spinner(dialogContext);
        String[] types = {"Song and Artist", "Genre"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(dialogContext, android.R.layout.simple_spinner_item, types);
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

        builder.setTitle("Create your equalizer")
                .setView(layout)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Create", (dialog, which) -> {
                    String name = songNameInput.getText().toString().trim();
                    String artist = artistNameInput.getText().toString().trim();
                    if (name.isEmpty()) name = "Untitled";

                    int type = typeSpinner.getSelectedItemPosition();

                    SelectedEqualizer existingMatch = findMatchingPreset(name, artist, type, presets);

                    List<Integer> initialLevels = existingMatch != null
                            ? new ArrayList<>(nonNullLevels(existingMatch.getBandLevels()))
                            : zeroLevels();

                    SelectedEqualizer eq = new SelectedEqualizer(name, artist, type, initialLevels);
                    eq.setFolderId(expandedFolderId);
                    if (existingMatch != null) {
                        eq.setLinkedPresetId(existingMatch.getId());
                    }

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
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_AutoEQ_Dialog);
        final EditText folderNameInput = new EditText(builder.getContext());
        folderNameInput.setHint("Folder Name");

        builder.setTitle("Create Folder")
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

    /**
     * Playlist names as native multi-choice items - Android renders these as
     * a checkbox with a checkmark per row, which is exactly the visual cue
     * asked for, with no custom row layout needed. Import/Cancel sit on
     * opposite sides of the bottom bar via setPositiveButton/setNegativeButton.
     */
    private void showPlaylistPickerDialog(String accessToken, List<SpotifyWebApiClient.SpotifyPlaylist> spotifyPlaylists) {
        if (spotifyPlaylists.isEmpty()) {
            Toast.makeText(requireContext(), "No playlists found on this Spotify account", Toast.LENGTH_LONG).show();
            return;
        }

        // A playlist already tied to a Folder (see finishPlaylistImport) can't
        // be picked again - re-importing it wouldn't do anything a Firebase
        // listener update doesn't already do, and risked confusing "did that
        // work?" re-import attempts.
        Set<String> alreadyImportedIds = new HashSet<>();
        for (Folder folder : folders) {
            if (folder.getSpotifyPlaylistId() != null) alreadyImportedIds.add(folder.getSpotifyPlaylistId());
        }

        List<SpotifyWebApiClient.SpotifyPlaylist> importable = new ArrayList<>();
        for (SpotifyWebApiClient.SpotifyPlaylist playlist : spotifyPlaylists) {
            if (!alreadyImportedIds.contains(playlist.id)) importable.add(playlist);
        }

        int alreadyImportedCount = spotifyPlaylists.size() - importable.size();
        if (importable.isEmpty()) {
            Toast.makeText(requireContext(), "All " + spotifyPlaylists.size() + " playlists on this account are already imported", Toast.LENGTH_LONG).show();
            return;
        }
        if (alreadyImportedCount > 0) {
            Toast.makeText(requireContext(),
                    "Hiding " + alreadyImportedCount + " already-imported playlist" + (alreadyImportedCount == 1 ? "" : "s"),
                    Toast.LENGTH_SHORT).show();
        }

        String[] names = new String[importable.size()];
        boolean[] checkedPlaylists = new boolean[importable.size()];
        for (int i = 0; i < importable.size(); i++) {
            names[i] = importable.get(i).name;
        }

        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_AutoEQ_Dialog)
                .setTitle("Choose playlists")
                .setMultiChoiceItems(names, checkedPlaylists, (dialog, which, isChecked) -> checkedPlaylists[which] = isChecked)
                .setPositiveButton("Import", (dialog, which) -> {
                    List<SpotifyWebApiClient.SpotifyPlaylist> selected = new ArrayList<>();
                    for (int i = 0; i < checkedPlaylists.length; i++) {
                        if (checkedPlaylists[i]) selected.add(importable.get(i));
                    }
                    if (selected.isEmpty()) {
                        Toast.makeText(requireContext(), "No playlists selected", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    showGenreEqPromptDialog(accessToken, selected);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /**
     * Asked once per import batch, right before the actual fetch starts.
     * "Yes" looks up each imported song's genre (via Last.fm's per-track
     * tags - see LastFmApiClient for why Spotify's own artist genres aren't
     * used) and seeds new presets from the matching GenrePresets bucket
     * instead of flat zero; "No" (or no match found for a given song) keeps
     * today's behavior. Either way every preset stays fully editable after.
     */
    private void showGenreEqPromptDialog(String accessToken, List<SpotifyWebApiClient.SpotifyPlaylist> playlists) {
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_AutoEQ_Dialog)
                .setTitle("Apply genre EQ?")
                .setMessage("Start each song's preset from a generic equalizer matched to its own genre, instead of flat? Songs with no genre match default to flat either way, and you can still edit any preset afterward.")
                .setNegativeButton("No", (dialog, which) -> importPlaylists(accessToken, playlists, false))
                .setPositiveButton("Yes", (dialog, which) -> {
                    if (BuildConfig.LASTFM_API_KEY == null || BuildConfig.LASTFM_API_KEY.isEmpty()) {
                        Toast.makeText(requireContext(), "LASTFM_API_KEY is not set in local.properties - importing without genre EQ", Toast.LENGTH_LONG).show();
                        importPlaylists(accessToken, playlists, false);
                        return;
                    }
                    importPlaylists(accessToken, playlists, true);
                })
                .show();
    }

    /** Reports how many presets one playlist's import produced, so a multi-playlist import can total them up into a single summary at the end. */
    private interface ImportCompletionCallback {
        void onComplete(int created, int linked);
    }

    /**
     * Aggregated across the whole import batch so the results can report
     * Last.fm's own tag coverage directly - whether nothing matched because
     * Last.fm had no tags for these songs, vs. had tags but none of them
     * were genre-like, vs. matched fine. That distinction is the whole
     * point: it tells the user (and me, without needing a logcat pull)
     * whether a "still flat" report is this app's bug, missing data
     * upstream, or a GenrePresets bucket that needs adding.
     */
    private static final class GenreMatchStats {
        private static final int MAX_UNMATCHED_ENTRIES_SHOWN = 25;

        int songsChecked;
        int songsWithTagData;
        int songsMatched;
        // Which tier of the track -> album -> artist -> Discogs fallback
        // chain (see fetchTagsWithFallbackChain) resolved each match - lets
        // the results dialog show whether the later, more expensive tiers
        // are actually pulling their weight.
        int matchedViaTrackTags;
        int matchedViaAlbumTags;
        int matchedViaArtistTags;
        int matchedViaDiscogs;
        // Name + artist + source + raw tags for every song that had tag data
        // but matched no bucket - shown directly in the results dialog
        // instead of relying on logcat, which several real devices (this one
        // included) filter aggressively for third-party apps regardless of
        // log level.
        final List<String> unmatchedWithTags = new ArrayList<>();

        String summarize() {
            StringBuilder sb = new StringBuilder("Matched ")
                    .append(songsMatched).append("/").append(songsChecked).append(" songs to a genre.");
            if (songsMatched > 0) {
                List<String> breakdown = new ArrayList<>();
                if (matchedViaTrackTags > 0) breakdown.add(matchedViaTrackTags + " by track");
                if (matchedViaAlbumTags > 0) breakdown.add(matchedViaAlbumTags + " by album");
                if (matchedViaArtistTags > 0) breakdown.add(matchedViaArtistTags + " by artist");
                if (matchedViaDiscogs > 0) breakdown.add(matchedViaDiscogs + " via Discogs");
                sb.append(" (").append(String.join(", ", breakdown)).append(")");
            }
            int noData = songsChecked - songsWithTagData;
            if (noData > 0) {
                sb.append("\n\n").append(noData).append(" song").append(noData == 1 ? "" : "s")
                        .append(" had no tag data from any source.");
            }
            if (!unmatchedWithTags.isEmpty()) {
                sb.append("\n\n").append(unmatchedWithTags.size()).append(" song").append(unmatchedWithTags.size() == 1 ? "" : "s")
                        .append(" had tags, but none matched a genre preset:");
                // Capped, not the full list - a large playlist with many
                // misses could otherwise turn this into an unreadable wall
                // of text (and a surprisingly large string to build/hold)
                // instead of a quick diagnostic glance.
                int shown = Math.min(unmatchedWithTags.size(), MAX_UNMATCHED_ENTRIES_SHOWN);
                for (int i = 0; i < shown; i++) {
                    sb.append("\n• ").append(unmatchedWithTags.get(i));
                }
                int remaining = unmatchedWithTags.size() - shown;
                if (remaining > 0) {
                    sb.append("\n... and ").append(remaining).append(" more");
                }
            }
            return sb.toString();
        }
    }

    private void importPlaylists(String accessToken, List<SpotifyWebApiClient.SpotifyPlaylist> playlists, boolean applyGenreEq) {
        showProgressDialog("Importing " + playlists.size() + " playlist" + (playlists.size() == 1 ? "" : "s") + "...");
        importPlaylistsSequentially(accessToken, playlists, 0, new int[2], applyGenreEq, new GenreMatchStats());
    }

    private void showGenreMatchResultsDialog(GenreMatchStats genreStats) {
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_AutoEQ_Dialog)
                .setTitle("Genre EQ results")
                .setMessage(genreStats.summarize())
                .setPositiveButton("OK", null)
                .show();
    }

    /**
     * Imports playlists one at a time (rather than all at once) so the
     * progress dialog can show honest "playlist X of N" status and so one
     * playlist failing (network hiccup, deleted mid-import, etc.) doesn't
     * abort the rest of the batch - it's logged and skipped instead.
     * totals[0]/[1] accumulate created/linked counts across every playlist
     * for the single summary toast shown once the whole batch finishes.
     */
    private void importPlaylistsSequentially(String accessToken, List<SpotifyWebApiClient.SpotifyPlaylist> playlists,
                                              int index, int[] totals, boolean applyGenreEq, GenreMatchStats genreStats) {
        if (index >= playlists.size()) {
            if (!isAdded()) return;
            dismissProgressDialog();
            int total = totals[0] + totals[1];
            Toast.makeText(requireContext(),
                    "Imported " + total + " song" + (total == 1 ? "" : "s")
                            + " from " + playlists.size() + " playlist" + (playlists.size() == 1 ? "" : "s")
                            + (totals[1] > 0 ? " (" + totals[1] + " linked to existing presets)" : ""),
                    Toast.LENGTH_LONG).show();
            // A toast can't reliably show more than a line or two - the genre
            // breakdown needs its own dialog instead, which has no such limit.
            if (applyGenreEq && genreStats.songsChecked > 0) {
                showGenreMatchResultsDialog(genreStats);
            }
            return;
        }

        SpotifyWebApiClient.SpotifyPlaylist playlist = playlists.get(index);
        String progressPrefix = "(" + (index + 1) + "/" + playlists.size() + ") ";
        updateProgressDialog(progressPrefix + "Fetching \"" + playlist.name + "\"...", 0, 0);

        ImportCompletionCallback next = (created, linked) -> {
            totals[0] += created;
            totals[1] += linked;
            importPlaylistsSequentially(accessToken, playlists, index + 1, totals, applyGenreEq, genreStats);
        };

        spotifyWebApiClient.fetchPlaylistTracks(accessToken, playlist.id, new SpotifyWebApiClient.TracksCallback() {
            @Override
            public void onSuccess(List<SpotifyWebApiClient.SpotifyTrack> tracks) {
                if (!isAdded() || getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    if (applyGenreEq) {
                        matchTrackGenresThenFinish(playlist, tracks, progressPrefix, genreStats, next);
                    } else {
                        finishPlaylistImport(playlist, tracks, null, next);
                    }
                });
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("PLAYLIST_IMPORT", "Failed to load tracks for \"" + playlist.name + "\"", e);
                if (!isAdded() || getActivity() == null) return;
                getActivity().runOnUiThread(() -> next.onComplete(0, 0));
            }

            @Override
            public void onProgress(int fetchedSoFar, int total) {
                if (!isAdded() || getActivity() == null) return;
                getActivity().runOnUiThread(() -> updateProgressDialog(
                        progressPrefix + "Fetching \"" + playlist.name + "\"..." + (total > 0 ? " (" + fetchedSoFar + "/" + total + ")" : " (" + fetchedSoFar + ")"),
                        fetchedSoFar, total));
            }
        });
    }

    /**
     * Looks up each track's own genre via Last.fm (per-song, not per-artist
     * - see LastFmApiClient for why) and matches it to a GenrePresets
     * bucket, one song at a time. Repeated (artist, track) pairs within the
     * same playlist share one lookup instead of hitting Last.fm twice.
     * LastFmApiClient.fetchTrackTags never fails outright - a lookup
     * problem just resolves to an empty tag list - so there's nothing here
     * to retry or abort on, unlike the old Spotify per-artist version.
     */
    private void matchTrackGenresThenFinish(SpotifyWebApiClient.SpotifyPlaylist playlist,
                                             List<SpotifyWebApiClient.SpotifyTrack> tracks, String progressPrefix,
                                             GenreMatchStats genreStats, ImportCompletionCallback onComplete) {
        if (lastFmApiClient == null) {
            lastFmApiClient = new LastFmApiClient();
        }
        if (discogsApiClient == null) {
            discogsApiClient = new DiscogsApiClient();
        }
        matchTrackGenresOneByOne(playlist, tracks, 0, new HashMap<>(), new HashMap<>(), progressPrefix, genreStats, onComplete);
    }

    /** Tags plus which tier of the fallback chain they came from (null if none of the tiers found anything), so cached repeats still count toward GenreMatchStats correctly. */
    private static final class TagLookupResult {
        final List<String> tags;
        final String source; // "track" / "album" / "artist" / "discogs" / null
        TagLookupResult(List<String> tags, String source) {
            this.tags = tags;
            this.source = source;
        }
    }

    private interface TagLookupCallback {
        void onResult(TagLookupResult result);
    }

    private void matchTrackGenresOneByOne(SpotifyWebApiClient.SpotifyPlaylist playlist, List<SpotifyWebApiClient.SpotifyTrack> tracks,
                                           int index, Map<String, TagLookupResult> tagCache, Map<String, int[]> levelsByTrackKey,
                                           String progressPrefix, GenreMatchStats genreStats, ImportCompletionCallback onComplete) {
        if (index >= tracks.size()) {
            finishPlaylistImport(playlist, tracks, levelsByTrackKey, onComplete);
            return;
        }

        SpotifyWebApiClient.SpotifyTrack track = tracks.get(index);
        String key = trackKey(track);
        updateProgressDialog(progressPrefix + "Matching genres for \"" + playlist.name + "\" (" + index + "/" + tracks.size() + ")...",
                index, tracks.size());

        if (tagCache.containsKey(key)) {
            recordTrackMatch(track, key, tagCache.get(key), levelsByTrackKey, genreStats);
            // Posted rather than called directly - a run of cached hits (the
            // same song repeated many times in one playlist) would otherwise
            // recurse straight down the call stack instead of trampolining
            // through the message queue like the network path naturally does.
            if (isAdded() && getActivity() != null) {
                getActivity().runOnUiThread(() -> matchTrackGenresOneByOne(
                        playlist, tracks, index + 1, tagCache, levelsByTrackKey, progressPrefix, genreStats, onComplete));
            }
            return;
        }

        fetchTagsWithFallbackChain(track, result -> {
            tagCache.put(key, result);
            recordTrackMatch(track, key, result, levelsByTrackKey, genreStats);
            matchTrackGenresOneByOne(playlist, tracks, index + 1, tagCache, levelsByTrackKey, progressPrefix, genreStats, onComplete);
        });
    }

    /**
     * Track tags first (most song-specific), then album, then artist (widest
     * net, but also the least song-specific - an artist can span genres
     * across their catalog, which is exactly why it's tried last), then
     * Discogs as a last resort since its 60/min limit makes it too slow to
     * use for every song. Every step only runs if the one before it came
     * back completely empty. All the clients already resolve to an empty
     * list on any failure, so this never needs its own failure branch.
     */
    private void fetchTagsWithFallbackChain(SpotifyWebApiClient.SpotifyTrack track, TagLookupCallback callback) {
        lastFmApiClient.fetchTrackTags(BuildConfig.LASTFM_API_KEY, track.artist, track.name, trackTags -> {
            if (!isAdded() || getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (trackTags != null && !trackTags.isEmpty()) {
                    callback.onResult(tagResult(trackTags, "track"));
                } else {
                    fetchAlbumTagsThenFallback(track, callback);
                }
            });
        });
    }

    private void fetchAlbumTagsThenFallback(SpotifyWebApiClient.SpotifyTrack track, TagLookupCallback callback) {
        if (track.album == null || track.album.isEmpty()) {
            fetchArtistTagsThenFallback(track, callback);
            return;
        }
        lastFmApiClient.fetchAlbumTags(BuildConfig.LASTFM_API_KEY, track.artist, track.album, albumTags -> {
            if (!isAdded() || getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                if (albumTags != null && !albumTags.isEmpty()) {
                    callback.onResult(tagResult(albumTags, "album"));
                } else {
                    fetchArtistTagsThenFallback(track, callback);
                }
            });
        });
    }

    private void fetchArtistTagsThenFallback(SpotifyWebApiClient.SpotifyTrack track, TagLookupCallback callback) {
        lastFmApiClient.fetchArtistTags(BuildConfig.LASTFM_API_KEY, track.artist, artistTags -> {
            if (!isAdded() || getActivity() == null) return;
            getActivity().runOnUiThread(() -> {
                boolean hasArtistTags = artistTags != null && !artistTags.isEmpty();
                if (hasArtistTags || BuildConfig.DISCOGS_TOKEN.isEmpty()) {
                    callback.onResult(tagResult(artistTags, "artist"));
                    return;
                }
                discogsApiClient.fetchGenreTags(BuildConfig.DISCOGS_TOKEN, track.artist, track.name, discogsTags -> {
                    if (!isAdded() || getActivity() == null) return;
                    getActivity().runOnUiThread(() -> callback.onResult(tagResult(discogsTags, "discogs")));
                });
            });
        });
    }

    /** source is only kept when tags actually has something - an empty result is source-less no matter which tier produced it. */
    private static TagLookupResult tagResult(List<String> tags, String source) {
        return new TagLookupResult(tags, (tags != null && !tags.isEmpty()) ? source : null);
    }

    private void recordTrackMatch(SpotifyWebApiClient.SpotifyTrack track, String key, TagLookupResult result,
                                   Map<String, int[]> levelsByTrackKey, GenreMatchStats genreStats) {
        List<String> tags = result.tags;
        genreStats.songsChecked++;
        boolean hasTags = tags != null && !tags.isEmpty();
        if (hasTags) genreStats.songsWithTagData++;
        String genreName = GenrePresets.matchTags(tags);
        // Log.i, not Log.d - plenty of real devices default their global log
        // level to INFO and silently drop DEBUG-priority lines at the logd
        // daemon itself. Kept as a secondary source only - unmatchedWithTags
        // below is the primary one, since some devices filter third-party app
        // logs regardless of level and logcat access isn't guaranteed at all.
        Log.i("GENRE_MATCH", "track=" + key + " source=" + result.source
                + " tags=" + tags + " -> " + (genreName != null ? genreName : "no match"));
        if (genreName != null) {
            levelsByTrackKey.put(key, GenrePresets.bandLevelsFor(genreName));
            switch (result.source) {
                case "track": genreStats.matchedViaTrackTags++; break;
                case "album": genreStats.matchedViaAlbumTags++; break;
                case "artist": genreStats.matchedViaArtistTags++; break;
                case "discogs": genreStats.matchedViaDiscogs++; break;
            }
            genreStats.songsMatched++;
        } else if (hasTags) {
            genreStats.unmatchedWithTags.add(track.name + " - " + track.artist
                    + " (" + result.source + ": " + tags + ")");
        }
    }

    private static String trackKey(SpotifyWebApiClient.SpotifyTrack track) {
        return track.artist.toLowerCase(Locale.US) + "||" + track.name.toLowerCase(Locale.US);
    }

    /** Same key shape as trackKey(SpotifyTrack), null-safe - used by playlist sync to compare a SelectedEqualizer against a live Spotify track. */
    private static String trackKey(SelectedEqualizer eq) {
        String artist = eq.getArtist() == null ? "" : eq.getArtist();
        String name = eq.getName() == null ? "" : eq.getName();
        return artist.toLowerCase(Locale.US) + "||" + name.toLowerCase(Locale.US);
    }

    private void finishPlaylistImport(SpotifyWebApiClient.SpotifyPlaylist playlist, List<SpotifyWebApiClient.SpotifyTrack> tracks,
                                       Map<String, int[]> genreLevelsByTrackKey, ImportCompletionCallback onComplete) {
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
        // Seeds the stored baseline immediately with the playlist's current
        // snapshot, rather than leaving it null - a fresh import already
        // reflects the playlist's current contents.
        folder.setSnapshotId(playlist.snapshotId);
        Folder finalFolder = folder;

        dataHandler.saveFolder(folder, new EqualizerDataHandler.OperationCallback() {
            @Override
            public void onSuccess() {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> importTracksIntoFolder(finalFolder, tracks, genreLevelsByTrackKey, onComplete));
                }
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("PLAYLIST_IMPORT", "Could not save folder for \"" + playlist.name + "\"", e);
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> onComplete.onComplete(0, 0));
                }
            }
        });
    }

    /**
     * Builds every imported preset in memory, then writes them all in one
     * batched call (see EqualizerDataHandler.saveEqualizers) instead of one
     * Firebase write per track. Writing one at a time was what froze the UI
     * on large playlists: every single write re-fires the whole-list
     * listener, and each fire rebuilds the entire drawer menu from scratch -
     * for N tracks that's N full rebuilds instead of one.
     */
    private void importTracksIntoFolder(Folder folder, List<SpotifyWebApiClient.SpotifyTrack> tracks,
                                         Map<String, int[]> genreLevelsByTrackKey, ImportCompletionCallback onComplete) {
        updateProgressDialog("Saving " + tracks.size() + " song" + (tracks.size() == 1 ? "" : "s")
                + " into \"" + folder.getName() + "\"...", 0, 0);

        int created = 0;
        int linked = 0;
        // Tracks already queued this same import count as "existing" too, so
        // a playlist with the same song listed twice links the second one to
        // the first instead of creating two independent presets.
        List<SelectedEqualizer> combinedExisting = new ArrayList<>(presets);
        List<SelectedEqualizer> toSave = new ArrayList<>(tracks.size());

        for (SpotifyWebApiClient.SpotifyTrack track : tracks) {
            SelectedEqualizer existingMatch = findMatchingPreset(track.name, track.artist, 0, combinedExisting);

            List<Integer> initialLevels;
            if (existingMatch != null) {
                initialLevels = new ArrayList<>(nonNullLevels(existingMatch.getBandLevels()));
            } else {
                int[] genreLevels = genreLevelsByTrackKey != null ? genreLevelsByTrackKey.get(trackKey(track)) : null;
                initialLevels = genreLevels != null ? toLevelList(genreLevels) : zeroLevels();
            }

            SelectedEqualizer eq = new SelectedEqualizer(track.name, track.artist, 0, initialLevels);
            eq.setFolderId(folder.getId());
            eq.setAlbumArtUrl(track.albumArtUrl);
            // Assigned up front (push keys are generated locally, no network
            // round trip) so existingMatch.getId() below already resolves
            // correctly for duplicates found earlier in this same loop.
            eq.setId(dataHandler.generatePresetId());
            if (existingMatch != null) {
                eq.setLinkedPresetId(existingMatch.getId());
                linked++;
            } else {
                created++;
            }
            combinedExisting.add(eq);
            toSave.add(eq);
        }

        int finalCreated = created;
        int finalLinked = linked;
        dataHandler.saveEqualizers(toSave, new EqualizerDataHandler.OperationCallback() {
            @Override
            public void onSuccess() {
                if (!isAdded()) return;
                onComplete.onComplete(finalCreated, finalLinked);
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("PLAYLIST_IMPORT", "Failed to save imported presets for \"" + folder.getName() + "\"", e);
                if (!isAdded()) return;
                onComplete.onComplete(0, 0);
            }
        });
    }

    /** Name + artist read from an on-device audio file's metadata (or guessed from its filename), before it becomes a SelectedEqualizer. */
    private static final class LocalTrackInfo {
        final String name;
        final String artist;
        LocalTrackInfo(String name, String artist) {
            this.name = name;
            this.artist = artist;
        }
    }

    /**
     * Callback for the SAF multi-file picker (see localFilePickerLauncher).
     * No READ_EXTERNAL_STORAGE/READ_MEDIA_AUDIO permission is needed for
     * this - Storage Access Framework grants read access to exactly the
     * files the user picks, for as long as this activity is alive, which is
     * all reading tags once at import time needs.
     */
    private void onLocalFilesPicked(List<Uri> uris) {
        if (uris == null || uris.isEmpty() || !isAdded()) return;
        if (dataHandler == null) {
            dataHandler = new EqualizerDataHandler();
        }

        showProgressDialog("Reading " + uris.size() + " file" + (uris.size() == 1 ? "" : "s") + "...");

        // Tag reading is file I/O (MediaMetadataRetriever), so it happens off
        // the UI thread - same reasoning as everywhere else in this file that
        // hops to a background thread/dispatcher before touching the network
        // or disk, just a plain Thread here since there's no OkHttp call to
        // ride along with.
        new Thread(() -> {
            List<LocalTrackInfo> tracks = new ArrayList<>(uris.size());
            for (Uri uri : uris) {
                tracks.add(extractLocalTrackInfo(uri));
            }
            if (!isAdded() || getActivity() == null) return;
            getActivity().runOnUiThread(() -> findOrCreateLocalFilesFolder(folder -> importLocalTracks(folder, tracks)));
        }).start();
    }

    private interface FolderReadyCallback {
        void onReady(Folder folder);
    }

    private static final String LOCAL_FILES_FOLDER_NAME = "Local Files";

    /**
     * Every local-file import shares this one folder rather than getting its
     * own, unlike Spotify playlists - a picked batch of files has no single
     * name/ID to build a per-import folder around (see the "why did you
     * leave out folder auto generation" conversation this came out of).
     * Matched by the source marker, not by name, so the user renaming this
     * folder later doesn't cause a second one to get created next time.
     */
    private void findOrCreateLocalFilesFolder(FolderReadyCallback callback) {
        for (Folder folder : folders) {
            if ("local".equals(folder.getSource())) {
                callback.onReady(folder);
                return;
            }
        }

        Folder newFolder = new Folder(LOCAL_FILES_FOLDER_NAME, null);
        newFolder.setSource("local");
        dataHandler.saveFolder(newFolder, new EqualizerDataHandler.OperationCallback() {
            @Override
            public void onSuccess() {
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> callback.onReady(newFolder));
                }
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("LOCAL_IMPORT", "Could not create \"" + LOCAL_FILES_FOLDER_NAME + "\" folder", e);
                if (isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> callback.onReady(null));
                }
            }
        });
    }

    private LocalTrackInfo extractLocalTrackInfo(Uri uri) {
        String title = null;
        String artist = null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(requireContext(), uri);
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE);
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
        } catch (Exception e) {
            Log.w("LOCAL_IMPORT", "Failed to read tags from " + uri, e);
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // release() itself throwing isn't something a caller can act on.
            }
        }

        if (title == null || title.isEmpty()) title = displayNameFromUri(uri);
        // Empty, not "Unknown Artist" - Spotify's own local-files feature
        // represents a missing artist tag as an empty string (its local file
        // URI is literally spotify:local:{artist}:{album}:{title}:{duration},
        // blank when untagged), and getDisplayName() already omits the " -
        // artist" suffix entirely when artist is empty. Falling back to a
        // literal "Unknown Artist" string here would silently break matching
        // against that same file played as a Spotify local file, since the
        // two sides would disagree on what the artist is.
        if (artist == null) artist = "";
        return new LocalTrackInfo(title, artist);
    }

    /** Falls back to the file's display name (extension stripped) for files with no TITLE tag - an untagged .mp3 shouldn't just disappear from the import. */
    private String displayNameFromUri(Uri uri) {
        String displayName = null;
        try (Cursor cursor = requireContext().getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (nameIndex >= 0) displayName = cursor.getString(nameIndex);
            }
        } catch (Exception e) {
            Log.w("LOCAL_IMPORT", "Failed to read display name for " + uri, e);
        }
        if (displayName == null) displayName = uri.getLastPathSegment();
        if (displayName == null) return "Untitled";

        int dot = displayName.lastIndexOf('.');
        return dot > 0 ? displayName.substring(0, dot) : displayName;
    }

    /**
     * Same batch-build-then-one-write shape as importTracksIntoFolder, and
     * reuses the exact same findMatchingPreset dedup logic - a local file
     * that matches an existing Spotify-imported (or manually created) preset
     * by song+artist links to it exactly like a duplicate Spotify track
     * would, rather than starting over at 0 dB. No genre EQ lookup - out of
     * scope for this import path per how it was asked for. Every import
     * lands in the same shared folder (see findOrCreateLocalFilesFolder).
     */
    private void importLocalTracks(Folder folder, List<LocalTrackInfo> tracks) {
        if (folder == null) {
            dismissProgressDialog();
            Toast.makeText(requireContext(), "Could not create the \"" + LOCAL_FILES_FOLDER_NAME + "\" folder", Toast.LENGTH_LONG).show();
            return;
        }

        updateProgressDialog("Saving " + tracks.size() + " song" + (tracks.size() == 1 ? "" : "s")
                + " into \"" + folder.getName() + "\"...", 0, 0);

        int created = 0;
        int linked = 0;
        List<SelectedEqualizer> combinedExisting = new ArrayList<>(presets);
        List<SelectedEqualizer> toSave = new ArrayList<>(tracks.size());

        for (LocalTrackInfo track : tracks) {
            SelectedEqualizer existingMatch = findMatchingPreset(track.name, track.artist, 0, combinedExisting);

            List<Integer> initialLevels = existingMatch != null
                    ? new ArrayList<>(nonNullLevels(existingMatch.getBandLevels()))
                    : zeroLevels();

            SelectedEqualizer eq = new SelectedEqualizer(track.name, track.artist, 0, initialLevels);
            eq.setSource("local");
            eq.setFolderId(folder.getId());
            eq.setId(dataHandler.generatePresetId());
            if (existingMatch != null) {
                eq.setLinkedPresetId(existingMatch.getId());
                linked++;
            } else {
                created++;
            }
            combinedExisting.add(eq);
            toSave.add(eq);
        }

        int finalCreated = created;
        int finalLinked = linked;
        dataHandler.saveEqualizers(toSave, new EqualizerDataHandler.OperationCallback() {
            @Override
            public void onSuccess() {
                if (!isAdded()) return;
                dismissProgressDialog();
                int total = finalCreated + finalLinked;
                Toast.makeText(requireContext(),
                        "Imported " + total + " song" + (total == 1 ? "" : "s") + " into \"" + folder.getName() + "\""
                                + (finalLinked > 0 ? " (" + finalLinked + " linked to existing presets)" : ""),
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("LOCAL_IMPORT", "Failed to save imported local files", e);
                if (!isAdded()) return;
                dismissProgressDialog();
                Toast.makeText(requireContext(), "Failed to import: " + e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * The refresh icon's tap handler - the sole way playlist sync happens
     * now (see bindFolderRefreshAction for why the automatic background
     * version was removed). Uses the same requestSpotifyWebApiToken
     * (interactive-login-if-needed) request pattern the working manual
     * "Import Playlist" flow already uses.
     */
    private void onFolderRefreshClicked(Folder folder) {
        if (!isAdded() || !(requireActivity() instanceof MainActivity)) return;

        showProgressDialog("Checking \"" + folder.getName() + "\" for updates...");

        ((MainActivity) requireActivity()).requestSpotifyWebApiToken(new MainActivity.SpotifyTokenCallback() {
            @Override
            public void onTokenReady(String accessToken) {
                if (!isAdded()) return;
                if (spotifyWebApiClient == null) {
                    spotifyWebApiClient = new SpotifyWebApiClient();
                }
                syncSingleFolder(accessToken, folder);
            }

            @Override
            public void onTokenError(String message) {
                if (!isAdded()) return;
                dismissProgressDialog();
                Toast.makeText(requireContext(), "Spotify login failed: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Deliberately never trusts snapshot_id to decide whether it's worth
     * checking - Spotify's own community reports confirm that field can
     * stay stale for a while even after a playlist's actual contents
     * already changed, which is exactly what made a quick second tap of
     * this button silently report "nothing changed." Always fetches and
     * diffs the real track list directly instead.
     */
    private void syncSingleFolder(String accessToken, Folder folder) {
        updateProgressDialog("Syncing \"" + folder.getName() + "\"...", 0, 0);
        spotifyWebApiClient.fetchPlaylistTracks(accessToken, folder.getSpotifyPlaylistId(), new SpotifyWebApiClient.TracksCallback() {
            @Override
            public void onSuccess(List<SpotifyWebApiClient.SpotifyTrack> tracks) {
                if (!isAdded() || getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    // A fresh snapshot_id is still worth grabbing for the
                    // folder's stored baseline (keeps the automatic check's
                    // cheap path accurate going forward) - it just isn't
                    // gating anything here. If this particular request fails,
                    // keep whatever baseline was already stored rather than
                    // clobbering it with null.
                    spotifyWebApiClient.fetchPlaylistSnapshotId(accessToken, folder.getSpotifyPlaylistId(), freshSnapshotId -> {
                        if (!isAdded() || getActivity() == null) return;
                        String newSnapshotId = freshSnapshotId != null ? freshSnapshotId : folder.getSnapshotId();
                        int[] totals = new int[]{0, 0}; // [added, removed]
                        getActivity().runOnUiThread(() -> applySyncedTracks(folder, newSnapshotId, tracks, totals, () -> {
                            if (!isAdded()) return;
                            dismissProgressDialog();
                            if (totals[0] == 0 && totals[1] == 0) {
                                Toast.makeText(requireContext(), "\"" + folder.getName() + "\" is already up to date", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(requireContext(),
                                        "\"" + folder.getName() + "\" synced: " + totals[0] + " added"
                                                + (totals[1] > 0 ? ", " + totals[1] + " removed" : ""),
                                        Toast.LENGTH_LONG).show();
                            }
                        }));
                    });
                });
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("PLAYLIST_SYNC", "Manual sync failed for \"" + folder.getName() + "\"", e);
                if (!isAdded() || getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    dismissProgressDialog();
                    Toast.makeText(requireContext(), "Sync failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int fetchedSoFar, int total) {
                if (!isAdded() || getActivity() == null) return;
                getActivity().runOnUiThread(() -> updateProgressDialog(
                        "Syncing \"" + folder.getName() + "\"..." + (total > 0 ? " (" + fetchedSoFar + "/" + total + ")" : ""),
                        fetchedSoFar, total));
            }
        });
    }

    /**
     * Diffs a playlist's current Spotify tracks against what's already in
     * its folder: a current track with no matching preset in the folder is
     * new and gets added (reusing the same findMatchingPreset dedup as a
     * normal import - it can still link to an existing preset elsewhere in
     * the library instead of starting at 0 dB). A folder preset with no
     * matching current track was removed from the playlist on Spotify's
     * side - that one instance gets deleted outright (not just detached -
     * moving removed songs out of the folder instead of deleting them was
     * the original design, but it cluttered the top-level list enough that
     * outright deletion is what was actually wanted). This only ever
     * deletes the one row for this folder: if it's a linked duplicate, the
     * real data (and any other duplicates pointing at it) are untouched;
     * if it happens to own the data itself, that mirrors how manual
     * multi-select delete already works elsewhere in the app - it doesn't
     * re-point other duplicates either.
     */
    private void applySyncedTracks(Folder folder, String newSnapshotId, List<SpotifyWebApiClient.SpotifyTrack> currentTracks,
                                    int[] totals, Runnable onDone) {
        Set<String> currentKeys = new HashSet<>();
        for (SpotifyWebApiClient.SpotifyTrack track : currentTracks) {
            currentKeys.add(trackKey(track));
        }

        List<SelectedEqualizer> combinedExisting = new ArrayList<>(presets);
        List<SelectedEqualizer> toSave = new ArrayList<>();
        List<String> toDelete = new ArrayList<>();
        int added = 0;
        int removed = 0;

        for (SpotifyWebApiClient.SpotifyTrack track : currentTracks) {
            boolean alreadyInFolder = false;
            for (SelectedEqualizer eq : presets) {
                if (folder.getId().equals(eq.getFolderId()) && trackKey(eq).equals(trackKey(track))) {
                    alreadyInFolder = true;
                    break;
                }
            }
            if (alreadyInFolder) continue;

            SelectedEqualizer existingMatch = findMatchingPreset(track.name, track.artist, 0, combinedExisting);
            List<Integer> initialLevels = existingMatch != null
                    ? new ArrayList<>(nonNullLevels(existingMatch.getBandLevels()))
                    : zeroLevels();

            SelectedEqualizer eq = new SelectedEqualizer(track.name, track.artist, 0, initialLevels);
            eq.setFolderId(folder.getId());
            eq.setAlbumArtUrl(track.albumArtUrl);
            eq.setId(dataHandler.generatePresetId());
            if (existingMatch != null) {
                eq.setLinkedPresetId(existingMatch.getId());
            }
            combinedExisting.add(eq);
            toSave.add(eq);
            added++;
        }

        for (SelectedEqualizer eq : presets) {
            if (!folder.getId().equals(eq.getFolderId())) continue;
            if (currentKeys.contains(trackKey(eq))) continue;

            toDelete.add(eq.getId());
            removed++;
        }

        folder.setSnapshotId(newSnapshotId);
        totals[0] += added;
        totals[1] += removed;

        EqualizerDataHandler.OperationCallback saveFolderThenDone = new EqualizerDataHandler.OperationCallback() {
            @Override
            public void onSuccess() {
                onDone.run();
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("PLAYLIST_SYNC", "Failed to update snapshot for \"" + folder.getName() + "\"", e);
                onDone.run();
            }
        };

        EqualizerDataHandler.OperationCallback deleteRemovedThenSaveFolder = new EqualizerDataHandler.OperationCallback() {
            @Override
            public void onSuccess() {
                dataHandler.saveFolder(folder, saveFolderThenDone);
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("PLAYLIST_SYNC", "Failed to delete removed presets for \"" + folder.getName() + "\"", e);
                dataHandler.saveFolder(folder, saveFolderThenDone);
            }
        };

        if (toSave.isEmpty()) {
            dataHandler.deleteEqualizers(toDelete, deleteRemovedThenSaveFolder);
            return;
        }

        dataHandler.saveEqualizers(toSave, new EqualizerDataHandler.OperationCallback() {
            @Override
            public void onSuccess() {
                dataHandler.deleteEqualizers(toDelete, deleteRemovedThenSaveFolder);
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("PLAYLIST_SYNC", "Failed to save synced tracks for \"" + folder.getName() + "\"", e);
                onDone.run();
            }
        });
    }

    /**
     * Same song+artist (case-insensitive) for type 0, or same name for type 1
     * (genre). Returns the matching preset - always the one that actually
     * owns its data, never another duplicate - or null if there's no match.
     */
    private SelectedEqualizer findMatchingPreset(String name, String artist, int type, List<SelectedEqualizer> existing) {
        for (SelectedEqualizer eq : existing) {
            if (eq.getType() != type) continue;
            boolean nameMatches = eq.getName() != null && eq.getName().equalsIgnoreCase(name);
            if (!nameMatches) continue;

            boolean matches;
            if (type == 0) {
                String existingArtist = eq.getArtist() == null ? "" : eq.getArtist();
                String newArtist = artist == null ? "" : artist;
                matches = existingArtist.equalsIgnoreCase(newArtist);
            } else {
                matches = true;
            }

            if (matches) {
                // Point new duplicates directly at the real data owner rather
                // than chaining through another duplicate.
                return resolveDataSource(eq);
            }
        }
        return null;
    }

    private static List<Integer> zeroLevels() {
        List<Integer> levels = new ArrayList<>(EqBandConfig.NUM_BANDS);
        for (int i = 0; i < EqBandConfig.NUM_BANDS; i++) {
            levels.add(0);
        }
        return levels;
    }

    private static List<Integer> nonNullLevels(List<Integer> levels) {
        return levels != null ? levels : zeroLevels();
    }

    /** Copies a GenrePresets band array into a mutable, independent List<Integer> - each imported preset needs its own list, not a shared reference. */
    private static List<Integer> toLevelList(int[] levels) {
        List<Integer> list = new ArrayList<>(levels.length);
        for (int level : levels) list.add(level);
        return list;
    }

    private void buildBandUiFromSystemEqualizer() {
        bandsContainer.removeAllViews();
        if (systemEq == null) return;

        bandsContainer.setClipChildren(false);
        bandsContainer.setClipToPadding(false);

        if (curveView != null) {
            curveView.setBandCount(EqBandConfig.NUM_BANDS);
            curveView.setMaxProgress(SPAN);
        }

        for (int band = 0; band < EqBandConfig.NUM_BANDS; band++) {
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
            if (curveView != null) curveView.setProgress(finalBand, currentLevel - MIN_LEVEL);

            if (label != null) {
                label.setText(formatFrequencyLabel(EqBandConfig.BAND_FREQUENCIES_HZ[finalBand]));
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

                    if (curveView != null) curveView.setProgress(finalBand, progress);

                    if (systemEq != null) {
                        systemEq.setPreEqBandAllChannelsTo(finalBand,
                                new DynamicsProcessing.EqBand(true, EqBandConfig.BAND_FREQUENCIES_HZ[finalBand], levelToGainDb(targetLevel)));

                        if (currentEq != null && currentEq.getBandLevels() != null
                                && finalBand < currentEq.getBandLevels().size()) {
                            currentEq.getBandLevels().set(finalBand, targetLevel);
                        }
                    }

                    if (sharedTooltip != null) {
                        sharedTooltip.setText(formatLevelAsDb(targetLevel));
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
     * currently being dragged - to the right of it by default, except for
     * the rightmost band, which shows it on the left (inner) side instead
     * so it doesn't run off the edge of the screen. The leftmost band needs
     * no special case: showing it on the right already keeps it on-screen.
     * Doesn't track the thumb's vertical position; it's centered on the
     * band's height once per drag, since the user's own finger covers the
     * thumb while dragging anyway.
     */
    private void positionSharedTooltip(View bandView) {
        if (sharedTooltip == null || bandsContainer == null) return;

        boolean isRightmostBand = bandsContainer.indexOfChild(bandView) == bandsContainer.getChildCount() - 1;
        float bandLeftInGraph = bandsContainer.getX() + bandView.getX();

        float targetX = isRightmostBand
                ? bandLeftInGraph - sharedTooltip.getWidth()
                : bandLeftInGraph + bandView.getWidth();
        float targetY = bandsContainer.getY() + bandView.getY()
                + (bandView.getHeight() - sharedTooltip.getHeight()) / 2f;

        sharedTooltip.setX(targetX);
        sharedTooltip.setY(targetY);
    }

    /**
     * Tapping the song/artist name in the toolbar brings up actions scoped to
     * that one preset - just "Reset EQ" for now, styled as a list so more can
     * be added later without changing the entry point.
     */
    private void showSongOptionsDialog() {
        if (currentEq == null) return;

        String[] options = {"Reset EQ"};
        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_AutoEQ_Dialog)
                .setTitle(currentEq.getDisplayName())
                .setItems(options, (dialog, which) -> {
                    if (which == 0) showResetEqConfirmation();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showResetEqConfirmation() {
        if (currentEq == null) return;

        new MaterialAlertDialogBuilder(requireContext(), R.style.ThemeOverlay_AutoEQ_Dialog)
                .setTitle("Reset EQ?")
                .setMessage("Set every band back to 0 dB for \"" + currentEq.getDisplayName() + "\"?")
                .setNegativeButton("No", null)
                .setPositiveButton("Yes", (dialog, which) -> resetCurrentEqToZero())
                .show();
    }

    /**
     * Drives every band's seekbar back to the 0 dB position rather than
     * writing zeroed levels straight to systemEq/Firebase directly - going
     * through SeekBar.setProgress() reuses the exact same
     * onProgressChanged path a manual drag takes (live audio engine +
     * curve view + currentEq all update together), so this can't drift out
     * of sync with that logic. setProgress() doesn't trigger a save on its
     * own (only onStopTrackingTouch does, and this isn't a touch gesture),
     * hence the explicit persistCurrentBandLevels() call after.
     */
    private void resetCurrentEqToZero() {
        if (bandsContainer == null || currentEq == null) return;

        int zeroProgress = -MIN_LEVEL; // 0 dB, offset into the seekbar's 0..SPAN range
        for (int band = 0; band < EqBandConfig.NUM_BANDS; band++) {
            View bandView = bandsContainer.getChildAt(band);
            if (bandView == null) continue;
            SeekBar sb = bandView.findViewById(R.id.eq_band_seekbar);
            if (sb != null) sb.setProgress(zeroProgress);
        }

        persistCurrentBandLevels();
        Toast.makeText(requireContext(), "Reset \"" + currentEq.getDisplayName() + "\" to 0 dB", Toast.LENGTH_SHORT).show();
    }

    /**
     * Saves the full set of band levels for the current preset, read directly
     * from systemEq (the live audio engine) rather than trusting the in-memory
     * currentEq.bandLevels list to have stayed perfectly in sync. systemEq
     * always holds exactly EqBandConfig.NUM_BANDS valid bands, so this can never hand
     * Firebase a null or short-length list.
     */
    private void persistCurrentBandLevels() {
        if (currentEq == null || dataHandler == null || systemEq == null) return;

        List<Integer> freshLevels = new ArrayList<>(EqBandConfig.NUM_BANDS);
        for (int i = 0; i < EqBandConfig.NUM_BANDS; i++) {
            float gainDb = systemEq.getPreEqBandByChannelIndex(0, i).getGain();
            freshLevels.add(gainDbToLevel(gainDb));
        }

        SelectedEqualizer dataSource = resolveDataSource(currentEq);
        dataSource.setBandLevels(freshLevels);
        if (dataSource != currentEq) {
            currentEq.setBandLevels(freshLevels);
        }

        String presetId = dataSource.getId();
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
        if (dataHandler != null) {
            dataHandler.stopListening();
        }
        // Avoids a window leak - the dialog holds this destroyed view's
        // context, so it can't just be left showing.
        dismissProgressDialog();
        progressDialog = null;
        // Same reasoning as before: don't tear down the service connection
        // here, so the EQ keeps working while navigating to/from Settings.
        // Unbinding happens in onDestroy instead.
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (serviceBound) {
            if (spotifyService != null) {
                // The service outlives this Fragment - clear its reference to
                // us so it doesn't hold onto a destroyed Fragment indefinitely.
                spotifyService.setStateListener(null);
            }
            requireContext().unbindService(serviceConnection);
            serviceBound = false;
        }
    }
}