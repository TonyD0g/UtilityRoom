package org.sec;

import javafx.scene.control.TextArea;
import org.sec.Utils.RSAUtil;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.lang.reflect.Field;
import java.net.Socket;
import java.security.InvalidKeyException;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.Scanner;

public class Client  {
    public static Cipher cipher;
    public static Object getField(String fieldName) throws ClassNotFoundException, NoSuchFieldException, InstantiationException, IllegalAccessException {
        Class clazz = Class.forName("org.sec.Controller.ClientController");
        Field field =  clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(Main.controller);
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

            // todo 服务端发送aes密钥(aes密钥使用rsa加密),用于回传加密
            String aesKey = RSAUtil.decrypt(in.readUTF(),serverPublicKey);
            // 将字符串转换回密钥
            byte[] keyBytes = Base64.getDecoder().decode(aesKey);
            SecretKey secretKey = new SecretKeySpec(keyBytes, "AES");
            cipher.init(Cipher.DECRYPT_MODE, secretKey); // 创建解密器
            byte[] bys = new byte[1024];

            print.appendText("-------------------------------------\n客户端:(输入exit断开会话)");
            while (true) {
                text = sc.next();
                Date date = new Date();
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
                print.appendText("[+] " + dateFormat.format(date) + " 客户端发送消息: " + text);
                if (text.equals("exit")) {
                    break;
                } else {
                    // 客户端发送消息
                    out.writeUTF(RSAUtil.encrypt(text, serverPublicKey));
                    // Client显示接收到Server的数据
                    in.read(bys);
                    byte[] decrypted = cipher.doFinal(bys);
                    print.appendText("服务器回传： " + new String(decrypted));

                }
            }
            print.appendText("-------------------------------------");
        } catch (IOException ignored) {
        } catch (IllegalBlockSizeException | BadPaddingException | InvalidKeyException e) {
            throw new RuntimeException(e);
        } finally {
            client.close();
        }
    }
}
