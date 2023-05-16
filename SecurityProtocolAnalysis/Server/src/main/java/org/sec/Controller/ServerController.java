package org.sec.Controller;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import org.sec.Check.Verification;
import org.sec.Server;
import org.sec.Utils.stringUtils;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;

public class ServerController {
public static String text = new String();
    @FXML
    private Button Open;

    @FXML
    private TextArea print;

    @FXML
    private Button AddUser;

    @FXML
    private Button DeleteUser;

    @FXML
    private Button DeleteIP;

    @FXML
    private TextField UsernamePassword;

    @FXML
    private TextField IP;

    @FXML
    private TextField Port;

    @FXML
    void AddUser(ActionEvent event) throws SQLException, NoSuchFieldException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        String text = UsernamePassword.getText();
        String[] usernamePassword = stringUtils.splitBySymbol(text, " ");
        Verification.openConnection();
        Verification.addUser(usernamePassword[0], usernamePassword[1]);
        Verification.closeConnection();
    }

    @FXML
    void DeleteIP(ActionEvent event) throws SQLException, NoSuchFieldException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        String text = IP.getText();
        Verification.openConnection();
        Verification.deleteIp(text);
        Verification.closeConnection();
    }

    @FXML
    void DeleteUser(ActionEvent event) throws SQLException, NoSuchFieldException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        String text = UsernamePassword.getText();
        String[] usernamePassword = stringUtils.splitBySymbol(text, " ");
        Verification.openConnection();
        Verification.deleteUser(usernamePassword[0]);
        Verification.closeConnection();
    }

    @FXML
    void Open(ActionEvent event) {
        MyThread t01 = new MyThread();
        t01.start();
    }
    class MyThread extends Thread{
        public MyThread() {
        }

        //run方法是每个线程运行过程中都必须执行的方法
        @Override
        public void run() {
            text = Port.getText();
            Server.connect(Integer.parseInt(text));
        }
    }
}

