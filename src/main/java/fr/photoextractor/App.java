package fr.photoextractor;

import fr.photoextractor.photos.PhotosLibrary;
import fr.photoextractor.ui.MainWindow;
import javafx.application.Application;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.bytedeco.ffmpeg.global.avutil;

public class App extends Application {

    private MainWindow window;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        avutil.av_log_set_level(avutil.AV_LOG_ERROR);
        window = new MainWindow(new PhotosLibrary());
        Scene scene = new Scene(window.root(), 1200, 760);
        scene.getStylesheets().add(App.class.getResource("/app.css").toExternalForm());
        window.installShortcuts(scene);
        stage.setTitle("Photo Extractor");
        stage.setMinWidth(820);
        stage.setMinHeight(520);
        stage.setScene(scene);
        stage.show();
        window.loadVideos();
    }

    @Override
    public void stop() {
        if (window != null) {
            window.dispose();
        }
    }
}
