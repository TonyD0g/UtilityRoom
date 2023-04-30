package org.sec.Communications;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;
import org.sec.Utils.RSAUtil;

import java.io.*;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Scanner;

public class Client extends Application {
    public static void main(String[] args) throws Exception {
        connect("127.0.0.1", 2333, "root", "root");
        launch(args);
    }
    public static void connect(String serverName, int port, String username, String password) throws IOException {
        Socket client = null;
        try {
            System.out.println("连接到主机：" + serverName + " ，端口号：" + port);
            client = new Socket(serverName, port);
            System.out.println("远程主机地址：" + client.getRemoteSocketAddress());
            OutputStream outToServer = client.getOutputStream();
            InputStream inFromServer = client.getInputStream();
            DataOutputStream out = new DataOutputStream(outToServer);   // out:Client发给Server
            DataInputStream in = new DataInputStream(inFromServer);     // in: Server发给Client
            Scanner sc = new Scanner(System.in);
            System.out.println("服务器响应： " + in.readUTF());
            String text;

            // rsa加密账号密码
            String serverPublicKey = in.readUTF(); // 接受服务端公钥
            System.out.println("[+] 正在尝试建立通讯,请稍等");
            out.writeUTF(RSAUtil.encrypt(username + " " + password, serverPublicKey) ); // 发送账号密码

            text = in.readUTF();
            if (text.contains("[-]")) {
                System.out.println(text);
                client.close();
                return;
            }

            System.out.println("-------------------------------------\n客户端:(输入exit断开会话)");
            while (true) {
                text = sc.next();
                Date date = new Date();
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
                System.out.println("[+] " + dateFormat.format(date) + " 客户端发送消息: " + text);
                if (text.equals("exit")) {
                    break;
                } else {
                    out.writeUTF(RSAUtil.encrypt(text, serverPublicKey));
                }
            }
            System.out.println("-------------------------------------");
        } catch (IOException ignored) {
        } finally {
            client.close();
        }
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        Parent root = FXMLLoader.load(getClass().getResource("SeaLice.fxml"));
        primaryStage.setScene(new Scene(root, 700, 575));
        primaryStage.show();
    }
}

