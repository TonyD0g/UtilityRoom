package org.sec;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.sec.Controller.ClientController;

import java.util.Objects;

public class Main extends Application {
    public static ClientController controller;

    @Override
    public void start(Stage primaryStage) throws Exception {
        //FXMLLoader必须使用参数初始化，否则getController会失败
        FXMLLoader loader = new FXMLLoader(Objects.requireNonNull(getClass().getClassLoader().getResource("Client.fxml")));
        Parent root = loader.load();
        //这个方法必须在load方法之后调用
        controller = loader.getController();
        primaryStage.setTitle("Client");
        primaryStage.setScene(new Scene(root, 650, 450));
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}


