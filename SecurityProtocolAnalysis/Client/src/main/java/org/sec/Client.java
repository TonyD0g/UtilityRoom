package org.sec;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import org.sec.Controller.ClientController;
import org.sec.Utils.FileUtils;
import org.sec.Utils.RSAUtil;

import java.io.*;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Scanner;

public class Client {
    public static TextArea print;
    public static List<String> ClientKey;
    public static String md51 = "";
    public static String md52 = "";
    public static String text;
    public static String serverPublicKey;
    public static DataOutputStream out;
    public static DataInputStream in;
    public static Socket client = null;
    public static Boolean isExit = false;
    public static Object getField(String fieldName) throws ClassNotFoundException, NoSuchFieldException, IllegalAccessException {
        Class clazz = Class.forName("org.sec.Controller.ClientController");
        Field field = clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(Main.controller);
    }

    public static void connect(String serverName, int port, String username, String password) throws IOException, NoSuchFieldException, ClassNotFoundException, InstantiationException, IllegalAccessException {

        print = (TextArea) getField("print");
        Client.isExit = false;

        try {
            print.appendText("连接到主机：" + serverName + " ，端口号：" + port + "\n");
            client = new Socket(serverName, port);
            print.appendText("远程主机地址：" + client.getRemoteSocketAddress() + "\n");
            OutputStream outToServer = client.getOutputStream();
            InputStream inFromServer = client.getInputStream();
            out = new DataOutputStream(outToServer);   // out:Client发给Server
            in = new DataInputStream(inFromServer);     // in: Server发给Client
            Scanner sc = new Scanner(System.in);
            String tmpIn = in.readUTF();
            Platform.runLater(() -> {
                print.appendText("服务器响应： " + tmpIn);
            });
            ClientKey = FileUtils.readLines(".\\ClientKey.txt", String.valueOf(StandardCharsets.UTF_8));   // 保存着客户端生成的rsa公钥私钥

            // rsa加密账号密码
            serverPublicKey = in.readUTF(); // 接受服务端公钥
            Platform.runLater(() -> print.appendText("[+] 正在尝试建立通讯,请稍等" + "\n"));
            out.writeUTF(RSAUtil.encrypt(username + " " + password, serverPublicKey)); // 发送账号密码

            text = in.readUTF();
            if (text.contains("[-]")) {
                print.appendText(text);
                client.close();
                return;
            }

            // 发送客户端公钥给客户端,用于给服务端去加密数据
            if (ClientKey != null) {
                out.writeUTF(RSAUtil.encrypt(ClientKey.get(0), serverPublicKey));
            }


            Platform.runLater(() -> print.appendText("\n-------------------------------------\n客户端:(输入exit断开会话)"));
            // todo 把这个while循环放入多线程中
            MyThread t01 = new MyThread();
            t01.start();

        } catch (IOException ignored) {
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String calculateMD5(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(text.getBytes());
        return org.sec.utils.stringUtils.byteArrayToHex(digest);
    }

}
class MyThread extends Thread{
    public MyThread() {
    }

    //run方法是每个线程运行过程中都必须执行的方法
    @Override
    public void run() {
        while (true) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (!ClientController.sc.equals("")) {
                Client.text = ClientController.sc;
                Date date = new Date();
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
                String finalText = Client.text;
                Platform.runLater(() -> Client.print.appendText("[+] " + dateFormat.format(date) + " 客户端发送消息: " + finalText));
                if (Client.text.equals("exit")) {
                    Client.isExit = true;
                    break;
                } else {
                    String decryptStr= null;
                    // 客户端发送消息,并进行完整性校验
                    try {
                        Client.out.writeUTF(RSAUtil.encrypt(Client.text, Client.serverPublicKey));
                        Client.md51 = Client.calculateMD5(Client.text);
                        // Client显示接收到Server的数据
                        decryptStr = RSAUtil.decrypt(Client.in.readUTF(), Client.ClientKey.get(1));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }

                    String finalDecryptStr = decryptStr;
                    Platform.runLater(() -> Client.print.appendText("[+] 服务器回传: " + finalDecryptStr + "\n"));
                    try {
                        Client.md52 = Client.calculateMD5(decryptStr);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    if (Client.md51.equals(Client.md52)) {
                        Platform.runLater(() -> Client.print.appendText("[+] 完整性校验成功,可继续通讯" + "\n"));
                    } else {
                        Platform.runLater(() -> Client.print.appendText("[-] 完整性校验失败,可能存在网络攻击行为,已断开通讯" + "\n"));
                        break;
                    }
                }
                ClientController.sc = "";
            }
        }
        Platform.runLater(() -> Client.print.appendText("\n-------------------------------------" + "\n"));
        if(Client.isExit){
            try {
                Client.client.close();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}