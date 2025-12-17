package com.example.photos.ui.albums;

import android.os.Bundle;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.photos.R;
import com.example.photos.db.PhotosDb;
import com.google.android.material.appbar.MaterialToolbar;

public class CategoryPhotosActivity extends AppCompatActivity {

    public static final String EXTRA_CATEGORY = "extra_category";
    private String categoryKey = "";
    private MaterialToolbar toolbar;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_category_photos);

        View root = findViewById(R.id.categoryPhotosRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        MaterialToolbar toolbar = findViewById(R.id.categoryPhotosTopAppBar);
        this.toolbar = toolbar;
        setSupportActionBar(toolbar);
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setDisplayShowHomeEnabled(true);
        }
        toolbar.setNavigationIcon(R.drawable.ic_chevron_left);
        toolbar.setNavigationOnClickListener(v -> finish());

        String category = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_CATEGORY);
        categoryKey = category == null ? "" : category;
        updateTitle();

        if (savedInstanceState == null) {
            CategoryPhotosFragment fragment = new CategoryPhotosFragment();
            Bundle args = new Bundle();
            args.putString(CategoryPhotosFragment.ARG_CATEGORY, categoryKey);
            fragment.setArguments(args);
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.categoryPhotosContainer, fragment)
                    .commit();
        }

        ViewCompat.requestApplyInsets(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateTitle();
    }

    private void updateTitle() {
        String displayName = CategoryDisplay.displayOf(categoryKey);
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = categoryKey == null ? "" : categoryKey;
        }
        if (displayName == null || displayName.trim().isEmpty()) {
            displayName = getString(R.string.title_albums);
        }
        setTitle(displayName);
        if (toolbar != null) {
            toolbar.setTitle(displayName);
        }
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setTitle(displayName);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.category_photos_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == android.R.id.home) {
            finish();
            return true;
        }
        if (id == R.id.action_multi_select) {
            CategoryPhotosFragment fragment = currentCategoryFragment();
            if (fragment != null) {
                boolean enabled = fragment.toggleMultiSelect();
                android.widget.Toast.makeText(this,
                        enabled ? R.string.multi_select_on : R.string.multi_select_off,
                        android.widget.Toast.LENGTH_SHORT).show();
            }
            return true;
        }
        if (id == R.id.action_clear_category_records) {
            showClearRecordsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    private CategoryPhotosFragment currentCategoryFragment() {
        androidx.fragment.app.Fragment f = getSupportFragmentManager().findFragmentById(R.id.categoryPhotosContainer);
        if (f instanceof CategoryPhotosFragment) {
            return (CategoryPhotosFragment) f;
        }
        return null;
    }

    private void showClearRecordsDialog() {
        android.view.View dialogView = android.view.LayoutInflater.from(this)
                .inflate(R.layout.dialog_delete_confirm, null, false);
        TextView message = dialogView.findViewById(R.id.deleteConfirmMessage);
        androidx.appcompat.widget.AppCompatButton positive = dialogView.findViewById(R.id.deleteConfirmPositive);
        androidx.appcompat.widget.AppCompatButton negative = dialogView.findViewById(R.id.deleteConfirmNegative);
        String displayName = CategoryDisplay.displayOf(categoryKey);
        if (message != null) {
            message.setText(getString(R.string.album_clear_records_confirm_message, displayName));
        }
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(dialogView)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }
        if (negative != null) {
            negative.setText(R.string.delete_confirm_negative);
            negative.setOnClickListener(v -> dialog.dismiss());
        }
        if (positive != null) {
            positive.setText(R.string.ok);
            positive.setOnClickListener(v -> {
                dialog.dismiss();
                clearRecordsAsync();
            });
        }
        dialog.show();
    }

    private void clearRecordsAsync() {
        final String key = categoryKey == null ? "" : categoryKey;
        final String displayName = CategoryDisplay.displayOf(key);
        java.util.concurrent.Executors.newSingleThreadExecutor().execute(() -> {
            try {
                PhotosDb.get(getApplicationContext()).categoryDao().deleteByCategory(key);
            } catch (Throwable ignored) {
            }
            runOnUiThread(() -> {
                CategoryPhotosFragment fragment = currentCategoryFragment();
                if (fragment != null) {
                    fragment.reload();
                }
                android.widget.Toast.makeText(this,
                        getString(R.string.album_clear_records_done, displayName),
                        android.widget.Toast.LENGTH_SHORT).show();
            });
        });
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
