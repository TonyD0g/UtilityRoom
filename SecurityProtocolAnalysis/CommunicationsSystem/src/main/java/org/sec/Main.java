package org.sec;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.util.Objects;

/**
 * @author Dianzhi Han
 * @version 1.0
 * @description: TODO
 * @date 2022/11/18 12:45
 */

public class Main extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception{
        Parent root = FXMLLoader.load(Objects.requireNonNull(getClass().getClassLoader().getResource("Client.fxml")));
        primaryStage.setTitle("test");
        primaryStage.setScene(new Scene(root, 750, 750));
        primaryStage.show();
    }
    public static void main(String[] args) {
        launch(args);
    }
}
