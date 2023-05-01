package org.sec;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TextArea;
import javafx.stage.Stage;
import org.sec.Utils.RSAUtil;

import java.io.*;
import java.lang.reflect.Field;
import java.net.Socket;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Objects;
import java.util.Scanner;

public class Client  {
    public static Object getField(String fieldName) throws ClassNotFoundException, NoSuchFieldException, InstantiationException, IllegalAccessException {
        Class clazz = Class.forName("org.sec.Controller.ClientController");
        Field field =  clazz.getField(fieldName);
        field.setAccessible(true);
        return field.get(clazz.newInstance());
    }
    public static void connect(String serverName, int port, String username, String password) throws IOException, NoSuchFieldException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        Socket client = null;
        TextArea print = (TextArea) getField("print");
        try {
            print.appendText("连接到主机：" + serverName + " ，端口号：" + port);
            client = new Socket(serverName, port);
            print.appendText("远程主机地址：" + client.getRemoteSocketAddress());
            OutputStream outToServer = client.getOutputStream();
            InputStream inFromServer = client.getInputStream();
            DataOutputStream out = new DataOutputStream(outToServer);   // out:Client发给Server
            DataInputStream in = new DataInputStream(inFromServer);     // in: Server发给Client
            Scanner sc = new Scanner(System.in);
            print.appendText("服务器响应： " + in.readUTF());
            String text;

            // rsa加密账号密码
            String serverPublicKey = in.readUTF(); // 接受服务端公钥
            print.appendText("[+] 正在尝试建立通讯,请稍等");
            out.writeUTF(RSAUtil.encrypt(username + " " + password, serverPublicKey) ); // 发送账号密码

            text = in.readUTF();
            if (text.contains("[-]")) {
                print.appendText(text);
                client.close();
                return;
            }

            print.appendText("-------------------------------------\n客户端:(输入exit断开会话)");
            while (true) {
                text = sc.next();
                Date date = new Date();
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
                print.appendText("[+] " + dateFormat.format(date) + " 客户端发送消息: " + text);
                if (text.equals("exit")) {
                    break;
                } else {
                    out.writeUTF(RSAUtil.encrypt(text, serverPublicKey));
                }
            }
            print.appendText("-------------------------------------");
        } catch (IOException ignored) {
        } finally {
            client.close();
        }
    }
}
