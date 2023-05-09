package org.sec;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.sec.Controller.ServerController;

import java.util.Objects;

public class Main extends Application {
    public static ServerController controller;

    @Override
    public void start(Stage primaryStage) throws Exception {
        //FXMLLoader必须使用参数初始化，否则getController会失败
        FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(getClass().getClassLoader().getResource("Server.fxml")));
        Parent root = loader.load();
        //这个方法必须在load方法之后调用
        controller = loader.getController();
        primaryStage.setTitle("Server");
        primaryStage.setScene(new Scene(root, 850, 650));
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
