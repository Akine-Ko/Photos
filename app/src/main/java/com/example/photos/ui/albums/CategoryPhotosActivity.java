package com.example.photos.ui.albums;

import android.os.Bundle;
import android.view.View;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.photos.R;
import com.google.android.material.appbar.MaterialToolbar;

public class CategoryPhotosActivity extends AppCompatActivity {

    public static final String EXTRA_CATEGORY = "extra_category";

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
        setSupportActionBar(toolbar);
        ActionBar ab = getSupportActionBar();
        if (ab != null) {
            ab.setDisplayHomeAsUpEnabled(true);
            ab.setDisplayShowHomeEnabled(true);
        }
        toolbar.setNavigationIcon(R.drawable.ic_chevron_left);
        toolbar.setNavigationOnClickListener(v -> finish());

        String category = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_CATEGORY);
        if (category == null) category = "";
        toolbar.setTitle(CategoryDisplay.displayOf(category));

        if (savedInstanceState == null) {
            CategoryPhotosFragment fragment = new CategoryPhotosFragment();
            Bundle args = new Bundle();
            args.putString(CategoryPhotosFragment.ARG_CATEGORY, category);
            fragment.setArguments(args);
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.categoryPhotosContainer, fragment)
                    .commit();
        }

        ViewCompat.requestApplyInsets(root);
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right);
    }
}
