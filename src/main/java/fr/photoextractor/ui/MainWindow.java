package fr.photoextractor.ui;

import fr.photoextractor.photos.PhotoVideo;
import fr.photoextractor.photos.PhotosLibrary;
import fr.photoextractor.photos.ThumbnailLocator;
import fr.photoextractor.video.FrameExporter;
import fr.photoextractor.video.TimeFormat;
import fr.photoextractor.video.VideoFrame;
import fr.photoextractor.video.VideoPlayer;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/** The whole user interface: Photos video list on the left, player and controls on the right. */
public final class MainWindow {

    /** Preview size only: the extracted frame is always decoded again at full resolution. */
    private static final int MAX_DISPLAY_SIZE = 1280;
    /** After a user seek, ignore the player position for a moment so the slider does not jump back. */
    private static final long SEEK_SETTLE_NANOS = 300_000_000L;

    private final PhotosLibrary library;
    private final ExecutorService background = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "photos-task");
        t.setDaemon(true);
        return t;
    });

    private final ObservableList<PhotoVideo> videos = FXCollections.observableArrayList();
    private final FilteredList<PhotoVideo> filteredVideos = new FilteredList<>(videos);
    private final VideoGrid videoGrid = new VideoGrid(ThumbnailLocator.forDefaultLibrary());
    private final TextField search = new TextField();
    private final Label listPlaceholder = new Label("Chargement de la photothèque…");

    private final ImageView view = new ImageView();
    private final StackPane videoPane = new StackPane();
    private final Label videoPlaceholder = new Label("Choisissez une vidéo dans la liste");
    private final VBox busyOverlay = new VBox(12);
    private final Label busyLabel = new Label();

    private final Button prevButton = new Button("◀︎ 1 image");
    private final Button playButton = new Button("▶︎");
    private final Button nextButton = new Button("1 image ▶︎");
    private final Slider slider = new Slider(0, 1, 0);
    private final Label timeLabel = new Label("00:00.000 / 00:00.000");
    private final Button extractButton = new Button("Extraire la frame");
    private final Label status = new Label("Prêt");

    private final BorderPane root = new BorderPane();
    private final AtomicReference<VideoFrame> latestFrame = new AtomicReference<>();

    private VideoPlayer player;
    private PhotoVideo currentVideo;
    private Path currentFile;
    private WritableImage image;
    private boolean busy;
    private int openRequest;
    private boolean updatingSlider;
    private long lastUserSeekNanos;

    private final AnimationTimer refresh = new AnimationTimer() {
        @Override
        public void handle(long now) {
            refreshFromPlayer();
        }
    };

    public MainWindow(PhotosLibrary library) {
        this.library = library;
        root.setLeft(buildSidebar());
        root.setCenter(buildVideoArea());
        root.setBottom(buildControls());
        updateControls();
        refresh.start();
    }

    public Parent root() {
        return root;
    }

    /** Space: play/pause. Left/Right arrows: previous/next frame. */
    public void installShortcuts(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (player == null || busy || scene.getFocusOwner() instanceof TextInputControl) {
                return;
            }
            if (e.getCode() == KeyCode.SPACE) {
                togglePlay();
            } else if (e.getCode() == KeyCode.LEFT) {
                player.stepBackward();
            } else if (e.getCode() == KeyCode.RIGHT) {
                player.stepForward();
            } else {
                return;
            }
            e.consume();
        });
    }

    public void loadVideos() {
        listPlaceholder.setText("Chargement de la photothèque…");
        videos.clear();
        status.setText("Lecture de la photothèque Photos…");
        run(library::listVideos, list -> {
            videos.setAll(list);
            listPlaceholder.setText("Aucune vidéo dans la photothèque");
            status.setText(list.size() + " vidéo(s) dans Photos");
        }, e -> listPlaceholder.setText("Impossible de lire la photothèque"));
    }

    public void dispose() {
        refresh.stop();
        closePlayer();
        background.shutdownNow();
    }

    // ---- layout ---------------------------------------------------------------------------

    private Parent buildSidebar() {
        Label title = new Label("Vidéos de Photos");
        title.getStyleClass().add("sidebar-title");

        Button reload = new Button("↻");
        reload.getStyleClass().add("icon-button");
        reload.setOnAction(e -> loadVideos());
        HBox header = new HBox(8, title, spacer(), reload);
        header.setAlignment(Pos.CENTER_LEFT);

        search.setPromptText("Rechercher un nom de fichier");
        search.textProperty().addListener((obs, old, text) -> {
            String q = text.strip().toLowerCase(Locale.ROOT);
            filteredVideos.setPredicate(v -> q.isEmpty() || v.filename().toLowerCase(Locale.ROOT).contains(q));
        });

        videoGrid.setPlaceholder(listPlaceholder);
        videoGrid.setOnChoose(video -> {
            if (currentVideo == null || !video.id().equals(currentVideo.id())) {
                openVideo(video);
            }
        });
        filteredVideos.addListener((ListChangeListener<PhotoVideo>) c ->
                videoGrid.setVideos(filteredVideos));
        VBox.setVgrow(videoGrid.node(), Priority.ALWAYS);

        VBox sidebar = new VBox(10, header, search, videoGrid.node());
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(14));
        // Two tiles, the scroll bar and the paddings.
        sidebar.setPrefWidth(VideoGrid.contentWidth() + 28 + 28);
        sidebar.setMinWidth(VideoGrid.contentWidth() + 28 + 28);
        return sidebar;
    }

    private Parent buildVideoArea() {
        view.setPreserveRatio(true);
        view.setSmooth(true);
        videoPlaceholder.getStyleClass().add("placeholder");

        ProgressIndicator spinner = new ProgressIndicator();
        spinner.setMaxSize(48, 48);
        busyOverlay.getChildren().addAll(spinner, busyLabel);
        busyOverlay.setAlignment(Pos.CENTER);
        busyOverlay.getStyleClass().add("busy-overlay");
        busyOverlay.setVisible(false);

        videoPane.getChildren().addAll(videoPlaceholder, view, busyOverlay);
        videoPane.getStyleClass().add("video-pane");
        // Without this, the image size would prevent the window from shrinking.
        videoPane.setMinSize(0, 0);
        videoPane.widthProperty().addListener((obs, o, n) -> fitImage());
        videoPane.heightProperty().addListener((obs, o, n) -> fitImage());
        return videoPane;
    }

    private Parent buildControls() {
        playButton.getStyleClass().add("play-button");
        playButton.setOnAction(e -> togglePlay());
        prevButton.setOnAction(e -> player.stepBackward());
        nextButton.setOnAction(e -> player.stepForward());
        extractButton.getStyleClass().add("accent");
        extractButton.setOnAction(e -> extractFrame());

        slider.valueProperty().addListener((obs, old, value) -> {
            if (!updatingSlider && player != null) {
                lastUserSeekNanos = System.nanoTime();
                player.seek(Math.round(value.doubleValue() * 1_000_000));
            }
        });
        HBox.setHgrow(slider, Priority.ALWAYS);
        timeLabel.getStyleClass().add("time");

        HBox transport = new HBox(10, prevButton, playButton, nextButton, slider, timeLabel, extractButton);
        transport.setAlignment(Pos.CENTER_LEFT);

        status.getStyleClass().add("status");
        VBox controls = new VBox(8, transport, status);
        controls.getStyleClass().add("controls");
        controls.setPadding(new Insets(12, 16, 10, 16));
        return controls;
    }

    // ---- actions --------------------------------------------------------------------------

    private void openVideo(PhotoVideo video) {
        int request = ++openRequest;
        closePlayer();
        currentVideo = video;
        setBusy("Récupération de « " + video.filename() + " » depuis Photos…\n"
                + "(peut prendre du temps si la vidéo est dans iCloud)");
        run(() -> library.exportOriginal(video), file -> {
            if (request != openRequest) {
                deleteTempFile(file);
                return;
            }
            try {
                currentFile = file;
                player = VideoPlayer.open(file, MAX_DISPLAY_SIZE, new VideoPlayer.Listener() {
                    @Override
                    public void onFrame(VideoFrame frame) {
                        latestFrame.set(frame);
                    }

                    @Override
                    public void onError(Exception e) {
                        Platform.runLater(() -> showError("Erreur de lecture", e));
                    }
                });
                updatingSlider = true;
                slider.setMax(Math.max(0.001, player.durationMicros() / 1_000_000.0));
                slider.setValue(0);
                updatingSlider = false;
                videoPlaceholder.setVisible(false);
                status.setText(video.filename() + " · " + String.format(Locale.FRENCH, "%.2f", player.frameRate())
                        + " images/s");
            } catch (Exception e) {
                showError("Impossible d'ouvrir la vidéo", e);
            } finally {
                clearBusy();
            }
        }, e -> clearBusy());
    }

    private void togglePlay() {
        if (player == null) {
            return;
        }
        if (player.isPlaying()) {
            player.pause();
        } else {
            player.play();
        }
    }

    private void extractFrame() {
        if (player == null || player.isPlaying() || busy) {
            return;
        }
        long position = player.positionMicros();
        String name = FrameExporter.fileName(currentVideo.filename(), position);
        Path file = currentFile;
        String videoName = currentVideo.filename();
        setBusy("Ajout de l'image à Photos…");
        run(() -> {
            FrameExporter.export(file, videoName, position, library::importImage);
            return null;
        }, ignored -> {
            clearBusy();
            status.setText("✓ « " + name + " » ajoutée à Photos");
        }, e -> clearBusy());
    }

    private void closePlayer() {
        if (player != null) {
            player.close();
            player = null;
        }
        if (currentFile != null) {
            deleteTempFile(currentFile);
            currentFile = null;
        }
        currentVideo = null;
        latestFrame.set(null);
        image = null;
        view.setImage(null);
        videoPlaceholder.setVisible(true);
        timeLabel.setText("00:00.000 / 00:00.000");
    }

    // ---- display --------------------------------------------------------------------------

    private void refreshFromPlayer() {
        VideoFrame frame = latestFrame.getAndSet(null);
        if (frame != null) {
            draw(frame);
        }
        if (player != null) {
            long position = player.positionMicros();
            boolean userJustSeeked = System.nanoTime() - lastUserSeekNanos < SEEK_SETTLE_NANOS;
            if (!slider.isValueChanging() && !userJustSeeked) {
                updatingSlider = true;
                slider.setValue(position / 1_000_000.0);
                updatingSlider = false;
            }
            timeLabel.setText(TimeFormat.display(position) + " / " + TimeFormat.display(player.durationMicros()));
        }
        updateControls();
    }

    private void draw(VideoFrame frame) {
        if (image == null || image.getWidth() != frame.width() || image.getHeight() != frame.height()) {
            image = new WritableImage(frame.width(), frame.height());
            view.setImage(image);
            fitImage();
        }
        image.getPixelWriter().setPixels(0, 0, frame.width(), frame.height(),
                PixelFormat.getByteBgraInstance(), frame.bgra(), 0, frame.width() * 4);
    }

    private void fitImage() {
        int rotation = player != null ? player.rotation() : 0;
        boolean sideways = rotation % 180 != 0;
        double w = Math.max(1, videoPane.getWidth() - 24), h = Math.max(1, videoPane.getHeight() - 24);
        view.setRotate(rotation);
        view.setFitWidth(sideways ? h : w);
        view.setFitHeight(sideways ? w : h);
    }

    private void updateControls() {
        boolean noVideo = player == null || busy;
        boolean playing = player != null && player.isPlaying();
        playButton.setText(playing ? "❚❚" : "▶︎");
        playButton.setDisable(noVideo);
        prevButton.setDisable(noVideo);
        nextButton.setDisable(noVideo);
        slider.setDisable(noVideo);
        extractButton.setDisable(noVideo || playing);
        videoGrid.setDisable(busy);
    }

    private void setBusy(String message) {
        busy = true;
        busyLabel.setText(message);
        busyOverlay.setVisible(true);
        updateControls();
    }

    private void clearBusy() {
        busy = false;
        busyOverlay.setVisible(false);
        updateControls();
    }

    private void showError(String context, Throwable e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        status.setText("⚠︎ " + context + " : " + message);
    }

    // ---- helpers --------------------------------------------------------------------------

    @FunctionalInterface
    private interface Work<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    private interface Done<T> {
        void accept(T value);
    }

    /** Runs work off the UI thread; callbacks come back on the UI thread. Errors are shown in the status bar. */
    private <T> void run(Work<T> work, Done<T> onSuccess, Done<Throwable> onFailure) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return work.call();
            }
        };
        task.setOnSucceeded(e -> onSuccess.accept(task.getValue()));
        task.setOnFailed(e -> {
            showError("Erreur", task.getException());
            onFailure.accept(task.getException());
        });
        background.submit(task);
    }

    private static void deleteTempFile(Path file) {
        try {
            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
        } catch (IOException ignored) {
            // A leftover file in the temp folder is harmless.
        }
    }

    private static HBox spacer() {
        HBox s = new HBox();
        HBox.setHgrow(s, Priority.ALWAYS);
        return s;
    }
}
