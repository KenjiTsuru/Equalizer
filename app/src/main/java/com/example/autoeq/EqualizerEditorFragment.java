package com.example.autoeq;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.media.audiofx.DynamicsProcessing;
import android.os.Bundle;
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
import com.google.android.material.navigation.NavigationView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
        sharedTooltip = view.findViewById(R.id.eq_shared_tooltip);

        toolbar.setNavigationIcon(android.R.drawable.ic_menu_sort_by_size);
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
                    android.R.drawable.ic_media_next,
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
            folderItem.setActionView(buildDrawerRowView(
                    folder.getName(),
                    R.drawable.ic_chevron_right,
                    expanded ? 90f : 0f,
                    selectedFolderIds.contains(folder.getId()),
                    v -> onFolderRowClicked(folder),
                    v -> onFolderRowLongClicked(folder)
            ));

            if (!expanded) continue;

            for (SelectedEqualizer eq : presets) {
                if (!folder.getId().equals(eq.getFolderId())) continue;

                android.view.MenuItem presetItem = menu.add(groupId, dynamicId++, android.view.Menu.NONE, "");
                presetItem.setActionView(buildDrawerRowView(
                        "     " + eq.getDisplayName(),
                        android.R.drawable.ic_media_next,
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
                    android.R.drawable.ic_media_next,
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
        showProgressDialog("Fetching \"" + playlist.name + "\"...");

        spotifyWebApiClient.fetchPlaylistTracks(accessToken, playlist.id, new SpotifyWebApiClient.TracksCallback() {
            @Override
            public void onSuccess(List<SpotifyWebApiClient.SpotifyTrack> tracks) {
                if (!isAdded() || getActivity() == null) return;
                getActivity().runOnUiThread(() -> finishPlaylistImport(playlist, tracks));
            }

            @Override
            public void onFailure(Exception e) {
                if (!isAdded() || getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    dismissProgressDialog();
                    Toast.makeText(requireContext(), "Could not load playlist tracks: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onProgress(int fetchedSoFar, int total) {
                if (!isAdded() || getActivity() == null) return;
                getActivity().runOnUiThread(() -> updateProgressDialog(
                        "Fetching tracks..." + (total > 0 ? " (" + fetchedSoFar + "/" + total + ")" : " (" + fetchedSoFar + ")"),
                        fetchedSoFar, total));
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
                    getActivity().runOnUiThread(() -> {
                        dismissProgressDialog();
                        Toast.makeText(requireContext(), "Could not save folder: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    });
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
    private void importTracksIntoFolder(Folder folder, List<SpotifyWebApiClient.SpotifyTrack> tracks) {
        updateProgressDialog("Saving " + tracks.size() + " song" + (tracks.size() == 1 ? "" : "s") + "...", 0, 0);

        int created = 0;
        int linked = 0;
        // Tracks already queued this same import count as "existing" too, so
        // a playlist with the same song listed twice links the second one to
        // the first instead of creating two independent presets.
        List<SelectedEqualizer> combinedExisting = new ArrayList<>(presets);
        List<SelectedEqualizer> toSave = new ArrayList<>(tracks.size());

        for (SpotifyWebApiClient.SpotifyTrack track : tracks) {
            SelectedEqualizer existingMatch = findMatchingPreset(track.name, track.artist, 0, combinedExisting);

            List<Integer> initialLevels = existingMatch != null
                    ? new ArrayList<>(nonNullLevels(existingMatch.getBandLevels()))
                    : zeroLevels();

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
                dismissProgressDialog();
                int total = finalCreated + finalLinked;
                Toast.makeText(requireContext(),
                        "Imported " + total + " song" + (total == 1 ? "" : "s")
                                + (finalLinked > 0 ? " (" + finalLinked + " linked to existing presets)" : "")
                                + " into \"" + folder.getName() + "\"",
                        Toast.LENGTH_LONG).show();
            }

            @Override
            public void onFailure(Exception e) {
                Log.e("PLAYLIST_IMPORT", "Failed to save imported presets", e);
                if (!isAdded()) return;
                dismissProgressDialog();
                Toast.makeText(requireContext(), "Could not save imported songs: " + e.getMessage(), Toast.LENGTH_LONG).show();
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