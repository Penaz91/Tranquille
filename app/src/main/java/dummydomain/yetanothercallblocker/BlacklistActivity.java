package dummydomain.yetanothercallblocker;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.view.ActionMode;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.selection.StorageStrategy;
import androidx.recyclerview.widget.RecyclerView;

import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.util.List;

import dummydomain.yetanothercallblocker.data.BlacklistImporterExporter;
import dummydomain.yetanothercallblocker.data.BlacklistService;
import dummydomain.yetanothercallblocker.data.YacbHolder;
import dummydomain.yetanothercallblocker.data.db.BlacklistDao;
import dummydomain.yetanothercallblocker.data.db.BlacklistItem;
import dummydomain.yetanothercallblocker.event.BlacklistChangedEvent;
import dummydomain.yetanothercallblocker.utils.FileUtils;

public class BlacklistActivity extends AppCompatActivity {

    private static final int REQUEST_CODE_IMPORT = 1;

    private static final Logger LOG = LoggerFactory.getLogger(BlacklistActivity.class);

    private final Settings settings = App.getSettings();
    private final BlacklistDao blacklistDao = YacbHolder.getBlacklistDao();
    private final BlacklistService blacklistService = YacbHolder.getBlacklistService();

    private BlacklistItemRecyclerViewAdapter blacklistAdapter;

    private SelectionTracker<Long> selectionTracker;
    private ActionMode.Callback actionModeCallback;
    private ActionMode actionMode;

    private AsyncTask<Void, Void, List<BlacklistItem>> loadBlacklistTask;

    public static Intent getIntent(Context context) {
        return new Intent(context, BlacklistActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_blacklist);

        blacklistAdapter = new BlacklistItemRecyclerViewAdapter(this::onItemClicked);
        RecyclerView recyclerView = findViewById(R.id.blacklistItemsList);
        recyclerView.setAdapter(blacklistAdapter);
        recyclerView.addItemDecoration(new CustomVerticalDivider(this));

        selectionTracker = new SelectionTracker.Builder<>(
                "blacklistSelection", recyclerView,
                blacklistAdapter.getItemKeyProvider(),
                blacklistAdapter.getItemDetailsLookup(recyclerView),
                StorageStrategy.createLongStorage())
                .build();

        blacklistAdapter.setSelectionTracker(selectionTracker);

        actionModeCallback = new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                mode.getMenuInflater().inflate(R.menu.activity_blacklist_action_mode, menu);
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                if (item.getItemId() == R.id.menu_delete) {
                    new AlertDialog.Builder(BlacklistActivity.this)
                            .setTitle(R.string.are_you_sure)
                            .setMessage(R.string.blacklist_delete_confirmation)
                            .setPositiveButton(R.string.yes, (dialog, which) -> {
                                if (selectionTracker.hasSelection()) {
                                    blacklistService.delete(selectionTracker.getSelection());
                                    selectionTracker.clearSelection();
                                    loadItems();
                                }
                            })
                            .setNegativeButton(R.string.no, null)
                            .show();
                    return true;
                }
                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                selectionTracker.clearSelection();
                actionMode = null;
            }
        };

        selectionTracker.addObserver(new SelectionTracker.SelectionObserver<Long>() {
            @Override
            public void onItemStateChanged(@NonNull Long key, boolean selected) {
                if (selectionTracker.hasSelection()) {
                    if (actionMode == null) {
                        actionMode = startSupportActionMode(actionModeCallback);
                    }
                } else {
                    if (actionMode != null) {
                        actionMode.finish();
                        actionMode = null;
                    }
                }

                if (actionMode != null) {
                    actionMode.setTitle(getString(R.string.selected_count,
                            selectionTracker.getSelection().size()));
                }
            }
        });

        selectionTracker.onRestoreInstanceState(savedInstanceState);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.activity_blacklist, menu);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.menu_block_blacklisted).setChecked(
                settings.getBlockBlacklisted());

        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    protected void onStart() {
        super.onStart();

        EventUtils.register(this);

        loadItems();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);

        selectionTracker.onSaveInstanceState(outState);
    }

    @Override
    protected void onStop() {
        EventUtils.unregister(this);

        super.onStop();
    }

    @Override
    protected void onDestroy() {
        cancelLoadingBlacklistTask();

        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_CODE_IMPORT && resultCode == Activity.RESULT_OK
                && data != null && data.getData() != null) {
            boolean error = false;

            ParcelFileDescriptor pfd = null;
            try {
                pfd = getContentResolver().openFileDescriptor(data.getData(), "r");
            } catch (FileNotFoundException e) {
                error = true;
                LOG.warn("onActivityResult() get file for import result", e);
            }

            if (pfd != null) {
                if (new BlacklistImporterExporter().importBlacklist(
                        YacbHolder.getBlacklistDao(), YacbHolder.getBlacklistService(),
                        pfd.getFileDescriptor())) {
                    Toast.makeText(this, R.string.done, Toast.LENGTH_SHORT).show();
                } else {
                    error = true;
                }
            }

            if (error) {
                Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN_ORDERED)
    public void onBlacklistChanged(BlacklistChangedEvent blacklistChangedEvent) {
        loadItems();
    }

    private void loadItems() {
        cancelLoadingBlacklistTask();
        @SuppressLint("StaticFieldLeak")
        AsyncTask<Void, Void, List<BlacklistItem>> loadBlacklistTask = this.loadBlacklistTask
                = new AsyncTask<Void, Void, List<BlacklistItem>>() {
            @Override
            protected List<BlacklistItem> doInBackground(Void... voids) {
                return blacklistDao.detach(blacklistDao.loadAll());
            }

            @Override
            protected void onPostExecute(List<BlacklistItem> items) {
                blacklistAdapter.setItems(items);
            }
        };
        loadBlacklistTask.execute();
    }

    private void cancelLoadingBlacklistTask() {
        if (loadBlacklistTask != null) {
            loadBlacklistTask.cancel(true);
            loadBlacklistTask = null;
        }
    }

    public void onBlockBlacklistedChanged(MenuItem item) {
        settings.setBlockBlacklisted(!item.isChecked());
    }

    public void onAddClicked(View view) {
        startActivity(EditBlacklistItemActivity.getIntent(this, null, null));
    }

    private void onItemClicked(BlacklistItem blacklistItem) {
        startActivity(EditBlacklistItemActivity.getIntent(this, blacklistItem.getId()));
    }

    public void onExportBlacklistClicked(MenuItem item) {
        File file = exportBlacklist();
        if (file != null) {
            FileUtils.shareFile(this, file);
        } else {
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
        }
    }

    private File exportBlacklist() {
        File file = new File(getCacheDir(), "YetAnotherCallBlocker_backup.csv");
        try {
            if (!file.exists() && !file.createNewFile()) return null;

            try (FileWriter writer = new FileWriter(file)) {
                if (new BlacklistImporterExporter().writeBackup(blacklistDao.loadAll(), writer)) {
                    return file;
                }
            }
        } catch (IOException e) {
            LOG.warn("exportBlacklist()", e);
        }

        return null;
    }

    public void onImportBlacklistClicked(MenuItem item) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        if (intent.resolveActivity(getPackageManager()) != null) {
            startActivityForResult(intent, REQUEST_CODE_IMPORT);
        } else {
            Toast.makeText(this, R.string.error, Toast.LENGTH_SHORT).show();
        }
    }

}
