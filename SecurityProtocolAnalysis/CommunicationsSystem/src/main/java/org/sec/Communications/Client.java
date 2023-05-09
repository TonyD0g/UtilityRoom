package org.sec.Communications;

import Utils.stringUtils;
import javafx.scene.control.TextArea;
import org.sec.Main;
import org.sec.Utils.FileUtils;
import org.sec.Utils.RSAUtil;

import javax.crypto.*;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.lang.reflect.Field;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.*;

public class Client {

    public static void main(String[] args) throws Exception {
        createKey(".\\ClientKey.txt"); // 查看是否已经生成公私钥,没有则生成
        connect("127.0.0.1", 2333, "root", "root");
    }

    public static void connect(String serverName, int port, String username, String password) throws IOException, NoSuchFieldException, ClassNotFoundException, InstantiationException, IllegalAccessException {
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

            List<String> ClientKey = FileUtils.readLines(".\\ClientKey.txt", String.valueOf(StandardCharsets.UTF_8));   // 保存着客户端生成的rsa公钥私钥

            // rsa加密账号密码
            String serverPublicKey = in.readUTF(); // 接受服务端公钥
            System.out.println("[+] 正在尝试建立通讯,请稍等");
            out.writeUTF(RSAUtil.encrypt(username + " " + password, serverPublicKey)); // 发送账号密码

            text = in.readUTF();
            if (text.contains("[-]")) { // 检测是否能正常连接
                System.out.println(text);
                client.close();
                return;
            }
            // 发送客户端公钥给客户端,用于给服务端去加密数据
            if (ClientKey != null) {
                out.writeUTF(RSAUtil.encrypt(ClientKey.get(0),serverPublicKey));
            }

            String md51 = "";
            String md52 = "";
            System.out.println("-------------------------------------\n开始正常通讯,请输入想要发送到服务端的消息\n客户端:(输入exit断开会话)");
            while (true) {
                text = sc.next();
                Date date = new Date();
                SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
                System.out.println("[+] " + dateFormat.format(date) + " 客户端发送消息: " + text);
                if (text.equals("exit")) {
                    break;
                } else {
                    // 客户端发送消息,并进行完整性校验
                    out.writeUTF(RSAUtil.encrypt(text, serverPublicKey));
                    md51 =  calculateMD5(text);
                    // Client显示接收到Server的数据
                    String decryptStr = RSAUtil.decrypt(in.readUTF(),ClientKey.get(1)) ;
                    System.out.println("[+] 服务器回传: " + decryptStr);
                    md52 = calculateMD5(decryptStr);
                    if(md51.equals(md52)){
                        System.out.println("[+] 完整性校验成功,可继续通讯");
                    }else {
                        System.out.println("[-] 完整性校验失败,可能存在网络攻击行为,已断开通讯,");
                        break;
                    }
                }
            }
            System.out.println("-------------------------------------");
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            client.close();
        }
    }
    public static String calculateMD5(String text) throws Exception {
        MessageDigest md = MessageDigest.getInstance("MD5");
        byte[] digest = md.digest(text.getBytes());
        return stringUtils.byteArrayToHex(digest);
    }

    public static void createKey(String filePath) throws Exception {
        List<String> lines = new ArrayList<>();
        File file = new File(filePath);
        Map<String, Object> keyMap;
        if (!file.exists()) {
            file.createNewFile();
            keyMap = RSAUtil.initKey();
            String publicKey = RSAUtil.getPublicKeyStr(keyMap);
            String privateKey = RSAUtil.getPrivateKeyStr(keyMap);
            lines.add(publicKey);
            lines.add(privateKey);
            FileUtils.writeLines(filePath, lines, false);
        }

    }
}
