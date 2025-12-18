package com.example.photos.ui.profile;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.google.android.material.slider.Slider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.example.photos.R;
import com.example.photos.classify.ClipClassifier;
import com.example.photos.db.PhotosDb;
import com.example.photos.search.HnswImageIndex;
import com.example.photos.settings.SearchPreferences;
import com.example.photos.sync.ClassificationWorker;
import com.example.photos.sync.ClipEmbeddingWorker;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.snackbar.Snackbar;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Simplified profile screen that only exposes backup and settings toggles.
 */
public class ProfileFragment extends Fragment {

    private View classifierProgressContainer;
    private android.widget.ProgressBar classifierProgressBar;
    private TextView classifierProgressLabel;
    private boolean embeddingRunning = false;
    private int embeddingProcessed = 0;
    private int embeddingTotal = 0;
    private boolean classificationRunning = false;
    private int classificationProcessed = 0;
    private int classificationTotal = 0;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        setupSearchLimit(view);
        setupClassifierSection(view);
        observeWorkerProgress();
    }

    private void setupSearchLimit(View root) {
        Slider slider = root.findViewById(R.id.profileSearchLimitSlider);
        if (slider == null) {
            return;
        }
        int[] options = SearchPreferences.getAllowedLimits();
        slider.setValueFrom(0f);
        slider.setValueTo(Math.max(0, options.length - 1));
        slider.setStepSize(1f);
        slider.setLabelFormatter(value -> {
            int idx = Math.max(0, Math.min(options.length - 1, Math.round(value)));
            return String.valueOf(options[idx]);
        });
        int current = SearchPreferences.getSearchLimit(requireContext());
        int idx = 0;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == current) {
                idx = i;
                break;
            }
        }
        slider.setValue(idx);
        slider.addOnChangeListener((s, value, fromUser) -> {
            int i = Math.max(0, Math.min(options.length - 1, Math.round(value)));
            int selected = options[i];
            SearchPreferences.setSearchLimit(requireContext(), selected);
            showSnackbar(s, getString(R.string.profile_search_limit_applied, selected));
        });
    }

    private void setupClassifierSection(View root) {
        MaterialButton embedButton = root.findViewById(R.id.profileRunEmbeddingButton);
        MaterialButton embedSampleButton = root.findViewById(R.id.profileRunEmbeddingSampleButton);
        MaterialButton runButton = root.findViewById(R.id.profileRunClassifierButton);
        MaterialButton sampleButton = root.findViewById(R.id.profileRunSampleButton);
        MaterialButton clearButton = root.findViewById(R.id.profileClearCategoriesButton);
        MaterialButton clearEmbeddingsButton = root.findViewById(R.id.profileClearEmbeddingsButton);
        TextView statusText = root.findViewById(R.id.profileClassifierStatusTextView);
        MaterialButton stopButton = root.findViewById(R.id.profileStopProgressButton);
        classifierProgressContainer = root.findViewById(R.id.profileClassifierProgressContainer);
        classifierProgressBar = root.findViewById(R.id.profileClassifierProgressBar);
        classifierProgressLabel = root.findViewById(R.id.profileClassifierProgressLabel);
        if (embedButton != null) {
            embedButton.setOnClickListener(v -> {
                ClipEmbeddingWorker.enqueueFull(requireContext());
                showSnackbar(v, getString(R.string.profile_embedding_job_scheduled));
            });
        }
        if (embedSampleButton != null) {
            embedSampleButton.setOnClickListener(v -> {
                ClipEmbeddingWorker.enqueueSample(requireContext(), 200, false);
                showSnackbar(v, getString(R.string.profile_embedding_sample_enqueued));
            });
        }
        if (runButton != null) {
            runButton.setOnClickListener(v -> {
                ClassificationWorker.enqueueFull(requireContext());
                showSnackbar(v, getString(R.string.profile_classifier_job_scheduled));
                refreshClassifierStatus(statusText);
            });
        }
        if (sampleButton != null) {
            sampleButton.setOnClickListener(v -> {
                ClassificationWorker.enqueueSample(requireContext(), 200);
                showSnackbar(v, getString(R.string.profile_classifier_sample_enqueued));
                refreshClassifierStatus(statusText);
            });
        }
        if (clearButton != null) {
            clearButton.setOnClickListener(v -> clearCategoriesAsync(v));
        }
        if (clearEmbeddingsButton != null) {
            clearEmbeddingsButton.setOnClickListener(v -> clearEmbeddingsAsync(v));
        }
        if (stopButton != null) {
            stopButton.setOnClickListener(v -> cancelRunningJobs());
        }
        refreshClassifierStatus(statusText);
    }

    private void observeWorkerProgress() {
        WorkManager wm = WorkManager.getInstance(requireContext());
        wm.getWorkInfosByTagLiveData(ClipEmbeddingWorker.TAG_EMBED)
                .observe(getViewLifecycleOwner(), infos -> {
                    updateProgressState(infos, true);
                    refreshProgressView();
                });
        wm.getWorkInfosByTagLiveData(ClassificationWorker.TAG_CLASSIFY)
                .observe(getViewLifecycleOwner(), infos -> {
                    updateProgressState(infos, false);
                    refreshProgressView();
                });
    }

    private void refreshClassifierStatus(@Nullable TextView statusView) {
        if (statusView == null) return;
        statusView.setText(R.string.profile_classifier_status_loading);
        Context app = requireContext().getApplicationContext();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            ClipClassifier.Status status = ClipClassifier.status(app);
            androidx.fragment.app.FragmentActivity activity = getActivity();
            if (activity == null || !isAdded()) {
                return;
            }
            activity.runOnUiThread(() -> applyClassifierStatus(statusView, status));
        });
        executor.shutdown();
    }

    private void applyClassifierStatus(TextView statusView, ClipClassifier.Status status) {
        if (status == null) {
            statusView.setText(R.string.profile_classifier_status_failed);
            return;
        }
        if (status.ready) {
            int count = status.categories == null ? 0 : status.categories.size();
            statusView.setText(getString(R.string.profile_classifier_status_ready, count));
        } else if (status.failed) {
            statusView.setText(R.string.profile_classifier_status_failed);
        } else {
            statusView.setText(R.string.profile_classifier_status_loading);
        }
    }

    private void updateProgressState(@Nullable java.util.List<WorkInfo> infos, boolean isEmbedding) {
        boolean running = false;
        int processed = 0;
        int total = 0;
        if (infos != null) {
            for (WorkInfo info : infos) {
                if (info == null) continue;
                if (info.getState() == WorkInfo.State.RUNNING) {
                    processed = info.getProgress().getInt("processed", 0);
                    total = info.getProgress().getInt("total", 0);
                    running = true;
                    break;
                }
            }
        }
        if (isEmbedding) {
            embeddingRunning = running;
            embeddingProcessed = processed;
            embeddingTotal = total;
        } else {
            classificationRunning = running;
            classificationProcessed = processed;
            classificationTotal = total;
        }
    }

    private void refreshProgressView() {
        if (embeddingRunning) {
            showProgress(embeddingProcessed, embeddingTotal);
        } else if (classificationRunning) {
            showProgress(classificationProcessed, classificationTotal);
        } else {
            hideProgress();
        }
    }

    private void showProgress(int processed, int total) {
        if (classifierProgressContainer == null || classifierProgressBar == null || classifierProgressLabel == null) {
            return;
        }
        classifierProgressContainer.setVisibility(View.VISIBLE);
        if (total > 0) {
            int pct = Math.min(100, Math.max(0, Math.round(processed * 100f / total)));
            classifierProgressBar.setProgress(pct);
            classifierProgressLabel.setText(getString(R.string.profile_progress_label_simple, processed, total));
        } else {
            classifierProgressBar.setProgress(0);
            classifierProgressLabel.setText(getString(R.string.profile_progress_unknown_simple, processed));
        }
    }

    private void hideProgress() {
        if (classifierProgressContainer != null) {
            classifierProgressContainer.setVisibility(View.GONE);
        }
    }

    private void clearCategoriesAsync(View anchor) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            boolean success = true;
            try {
                PhotosDb.get(requireContext().getApplicationContext()).categoryDao().clearAll();
            } catch (Throwable t) {
                success = false;
            }
            boolean finalSuccess = success;
            androidx.fragment.app.FragmentActivity activity = getActivity();
            if (activity == null || !isAdded()) return;
            activity.runOnUiThread(() -> {
                if (finalSuccess) {
                    showSnackbar(anchor, getString(R.string.profile_classifier_clear_done));
                } else {
                    showSnackbar(anchor, getString(R.string.profile_classifier_status_failed));
                }
            });
        });
        executor.shutdown();
    }

    private void clearEmbeddingsAsync(View anchor) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            boolean success = true;
            try {
                Context app = requireContext().getApplicationContext();
                PhotosDb db = PhotosDb.get(app);
                db.featureDao().deleteByType(com.example.photos.features.FeatureType.CLIP_IMAGE_EMB.getCode());
                db.featureDao().deleteByType(com.example.photos.features.FeatureType.DINO_IMAGE_EMB.getCode());
                db.featureDao().deleteByType(com.example.photos.features.FeatureType.FACE_SFACE_EMB.getCode());
                new HnswImageIndex(app, "dino_hnsw.index").clear();
                new HnswImageIndex(app, "clip_hnsw.index").clear();
                new HnswImageIndex(app, "face_hnsw.index").clear();
            } catch (Throwable t) {
                success = false;
            }
            boolean finalSuccess = success;
            androidx.fragment.app.FragmentActivity activity = getActivity();
            if (activity == null || !isAdded()) return;
            activity.runOnUiThread(() -> {
                if (finalSuccess) {
                    showSnackbar(anchor, getString(R.string.profile_features_clear_done));
                } else {
                    showSnackbar(anchor, getString(R.string.profile_classifier_status_failed));
                }
            });
        });
        executor.shutdown();
    }

    private void showSnackbar(View anchor, String message) {
        Snackbar.make(anchor, message, Snackbar.LENGTH_SHORT).show();
    }

    private void cancelRunningJobs() {
        WorkManager wm = WorkManager.getInstance(requireContext());
        wm.cancelAllWorkByTag(ClipEmbeddingWorker.TAG_EMBED);
        wm.cancelAllWorkByTag(ClassificationWorker.TAG_CLASSIFY);
        embeddingRunning = false;
        classificationRunning = false;
        hideProgress();
        showSnackbar(requireView(), getString(R.string.profile_stop_processing));
    }
}
