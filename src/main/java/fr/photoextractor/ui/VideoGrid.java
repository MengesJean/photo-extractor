package fr.photoextractor.ui;

import fr.photoextractor.photos.PhotoVideo;
import fr.photoextractor.photos.ThumbnailLocator;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Two-column grid of the Photos videos with their thumbnails. Rows are cells of a ListView so
 * that only the visible tiles exist and load their thumbnail.
 */
final class VideoGrid {

    private static final int COLUMNS = 2;
    private static final double TILE_SIZE = 156;
    private static final double GAP = 10;
    /** Width of the selection border drawn around the thumbnail. */
    private static final double BORDER = 3;
    private static final int CACHED_THUMBNAILS = 120;
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("d MMM yyyy · HH:mm", Locale.FRENCH).withZone(ZoneId.systemDefault());

    private final ThumbnailLocator thumbnails;
    private final ListView<List<PhotoVideo>> rows = new ListView<>();
    private final Map<String, Image> cache = new LinkedHashMap<>(CACHED_THUMBNAILS, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Image> eldest) {
            return size() > CACHED_THUMBNAILS;
        }
    };
    private Consumer<PhotoVideo> onChoose = v -> {
    };
    private PhotoVideo selected;

    VideoGrid(ThumbnailLocator thumbnails) {
        this.thumbnails = thumbnails;
        rows.getStyleClass().add("video-grid");
        rows.setFocusTraversable(false);
        rows.setCellFactory(lv -> new RowCell());
    }

    Node node() {
        return rows;
    }

    /** Width of the grid content, without the scroll bar. */
    static double contentWidth() {
        return COLUMNS * TILE_SIZE + (COLUMNS - 1) * GAP;
    }

    void setPlaceholder(Node placeholder) {
        rows.setPlaceholder(placeholder);
    }

    void setDisable(boolean disable) {
        rows.setDisable(disable);
    }

    void setOnChoose(Consumer<PhotoVideo> onChoose) {
        this.onChoose = onChoose;
    }

    void setVideos(List<PhotoVideo> videos) {
        List<List<PhotoVideo>> chunks = new ArrayList<>();
        for (int i = 0; i < videos.size(); i += COLUMNS) {
            chunks.add(List.copyOf(videos.subList(i, Math.min(i + COLUMNS, videos.size()))));
        }
        rows.getItems().setAll(chunks);
    }

    private void choose(PhotoVideo video) {
        selected = video;
        rows.refresh();
        onChoose.accept(video);
    }

    private boolean isSelected(PhotoVideo video) {
        return selected != null && selected.id().equals(video.id());
    }

    private Image thumbnail(PhotoVideo video) {
        Image cached = cache.get(video.id());
        if (cached != null) {
            return cached;
        }
        Optional<Path> file = thumbnails.find(video);
        if (file.isEmpty()) {
            return null;
        }
        // Loaded in the background by JavaFX; the tile shows its placeholder meanwhile.
        Image image = new Image(file.get().toUri().toString(), true);
        cache.put(video.id(), image);
        return image;
    }

    private final class RowCell extends ListCell<List<PhotoVideo>> {
        private final Tile[] tiles = new Tile[COLUMNS];
        private final HBox row = new HBox(GAP);

        RowCell() {
            for (int i = 0; i < COLUMNS; i++) {
                tiles[i] = new Tile();
                row.getChildren().add(tiles[i].root);
            }
            getStyleClass().add("grid-row");
        }

        @Override
        protected void updateItem(List<PhotoVideo> videos, boolean empty) {
            super.updateItem(videos, empty);
            if (empty || videos == null) {
                setGraphic(null);
                return;
            }
            for (int i = 0; i < COLUMNS; i++) {
                tiles[i].show(i < videos.size() ? videos.get(i) : null);
            }
            setGraphic(row);
        }
    }

    private final class Tile {
        private final ImageView image = new ImageView();
        private final Label placeholder = new Label("▶︎");
        private final StackPane frame = new StackPane(placeholder, image);
        private final Label name = new Label();
        private final Label date = new Label();
        private final VBox root = new VBox(4, frame, name, date);
        private PhotoVideo video;

        Tile() {
            double side = TILE_SIZE - 2 * BORDER;
            image.setFitWidth(side);
            image.setFitHeight(side);
            image.setSmooth(true);
            Rectangle corners = new Rectangle(side, side);
            corners.setArcWidth(10);
            corners.setArcHeight(10);
            image.setClip(corners);
            frame.setMinSize(TILE_SIZE, TILE_SIZE);
            frame.setMaxSize(TILE_SIZE, TILE_SIZE);
            frame.getStyleClass().add("tile-frame");
            placeholder.getStyleClass().add("tile-placeholder");
            name.getStyleClass().add("cell-name");
            date.getStyleClass().add("cell-date");
            name.setMaxWidth(TILE_SIZE);
            root.setPrefWidth(TILE_SIZE);
            root.setAlignment(Pos.TOP_LEFT);
            root.getStyleClass().add("tile");
            root.setOnMouseClicked(e -> {
                if (video != null) {
                    choose(video);
                }
            });
        }

        void show(PhotoVideo v) {
            video = v;
            root.setVisible(v != null);
            if (v == null) {
                return;
            }
            name.setText(v.filename());
            date.setText(v.date() != null ? DATE_FORMAT.format(v.date()) : "Date inconnue");
            root.getStyleClass().remove("selected");
            if (isSelected(v)) {
                root.getStyleClass().add("selected");
            }
            Image img = thumbnail(v);
            image.setImage(img);
            if (img != null) {
                cropToSquareWhenLoaded(img);
            }
        }

        /** Fills the square tile like Photos does: centered crop of the thumbnail. */
        private void cropToSquareWhenLoaded(Image img) {
            if (img.getWidth() > 0) {
                crop(img);
            } else {
                image.setViewport(null);
                img.widthProperty().addListener((obs, old, w) -> {
                    if (image.getImage() == img && w.doubleValue() > 0) {
                        crop(img);
                    }
                });
            }
        }

        private void crop(Image img) {
            double w = img.getWidth(), h = img.getHeight(), side = Math.min(w, h);
            image.setViewport(new Rectangle2D((w - side) / 2, (h - side) / 2, side, side));
        }
    }
}
