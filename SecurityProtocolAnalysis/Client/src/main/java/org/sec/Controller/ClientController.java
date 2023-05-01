package org.sec.Controller;

import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.AnchorPane;
import org.sec.Client;
import org.sec.Utils.stringUtils;

import java.io.IOException;

public class ClientController {

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
    void Click(ActionEvent event) throws IOException, NoSuchFieldException, ClassNotFoundException, InstantiationException, IllegalAccessException, IOException {
        String[] usernamePassword = stringUtils.splitBySymbol(UsernamePassword.getText(), " ");
        Client.connect(IP.getText(), Integer.parseInt(Port.getText()), usernamePassword[0], usernamePassword[1]);
    }

}
