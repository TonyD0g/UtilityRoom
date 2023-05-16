package org.sec.Controller;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.AnchorPane;
import org.sec.Client;

import java.io.IOException;

public class ClientController {
    public static String sc = new String();

    @FXML
    private AnchorPane Disconnect;

    @FXML
    private Button Send;

    @FXML
    private TextField IP;

    @FXML
    private TextField Port;

    @FXML
    private TextField UsernamePassword;

    @FXML
    private TextArea print;
    @FXML
    private Button SendMsg;
    @FXML
    private TextField msg;

    @FXML
    void Click(ActionEvent event) {
        MyThread t01 = new MyThread();
        t01.start();
    }
    @FXML
    void sendMsg(ActionEvent event) {
        MyThread2 t02 = new MyThread2();
        t02.start();
    }

    class MyThread extends Thread{
        public MyThread() {
        }

        //run方法是每个线程运行过程中都必须执行的方法
        @Override
        public void run() {
            String[] usernamePassword = org.sec.utils.stringUtils.splitBySymbol(UsernamePassword.getText(), " ");
            try {
                Client.connect(IP.getText(), Integer.parseInt(Port.getText()), usernamePassword[0], usernamePassword[1]);
            } catch (IOException | NoSuchFieldException | ClassNotFoundException | InstantiationException |
                     IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }
    class MyThread2 extends Thread{
        public MyThread2() {
        }

        //run方法是每个线程运行过程中都必须执行的方法
        @Override
        public void run() {
            sc = msg.getText();
        }
    }
}
