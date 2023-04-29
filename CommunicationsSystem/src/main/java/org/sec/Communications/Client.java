package org.sec.Communications;

import com.sun.xml.internal.bind.api.impl.NameConverter;
import org.sec.Utils.FileUtils;
import org.sec.Utils.RSAUtil;

import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;

public class Client {
    public static void main(String[] args) throws Exception {
        connect("127.0.0.1", 2333, "root", "root");
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
            String ServerPublicKey;

            // 发送账号密码
            out.writeUTF(username + " " + password);
            text = in.readUTF();
            if (text.contains("[-]")) {
                System.out.println(text);
                client.close();
                return;
            } else {
                ServerPublicKey = text;
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
                    out.writeUTF(RSAUtil.encrypt(text, ServerPublicKey));
                }
            }
            System.out.println("-------------------------------------");
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            client.close();
        }
    }
}

