package org.sec.Communications;

import org.sec.Check.VerificationUser;
import org.sec.Utils.FileUtils;
import org.sec.Utils.RSAUtil;
import org.sec.Utils.stringUtils;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

// todo 添加用户(涉及数据库操作),删除用户(涉及数据库操作),增加登录次数限制(防止弱口令爆破,从client.ip入手进行限制),解决密码交互中密码为明文的问题
public class Server extends Thread {
    public static void main(String args[]) {
        connect(2333);
    }

    public static void connect(int port) {
        try {
            Thread t = new Server(port);
            createKey(".\\ServerKey.txt"); // 查看是否已经生成公私钥,没有则生成
            t.run();
        } catch (IOException e) {
            e.printStackTrace();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private ServerSocket serverSocket;

    public Server(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setSoTimeout(1000000000);
    }

    public void run() {
        while (true) {
            try {
                System.out.println("等待远程连接，端口号为：" + serverSocket.getLocalPort() + "...");
                Socket server = serverSocket.accept();
                System.out.println("远程主机地址：" + server.getRemoteSocketAddress());
                DataInputStream in = new DataInputStream(server.getInputStream());
                DataOutputStream out = new DataOutputStream(server.getOutputStream());
                out.writeUTF("[+] 你已连接远程服务器: " + server.getLocalSocketAddress()); // 发送信息给Client
                String input, decryptText;
                List<String> ServerKey = FileUtils.readLines(".\\ServerKey.txt", String.valueOf(StandardCharsets.UTF_8));


                // 读取账号密码,并查看服务端数据库是否存在该用户,存在则继续,否则结束
                VerificationUser.openConnection();
                try {
                    input = in.readUTF();
                    String[] userInfo = stringUtils.splitBySymbol(input, " ");
                    if (VerificationUser.checkUserAndPass(userInfo[0], userInfo[1])) {
                        //out.writeUTF("[+] 服务端接受请求,正在建立通讯连接~");
                        // 发送服务器公钥给客户端,用于给客户端去加密数据
                        out.writeUTF(ServerKey.get(0));

                        System.out.println("-------------------------------------\n服务端:");
                        while (true) {
                            Date date = new Date();
                            SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
                            input = in.readUTF();
                            if (input.equals("exit")) {
                                server.close();
                                break;
                            } else {
                                decryptText = RSAUtil.decrypt(input, ServerKey.get(1));
                                System.out.println("[+] " + dateFormat.format(date) + " 客户端发来消息: " + decryptText);
                            }
                        }
                        System.out.println("-------------------------------------");
                    } else {
                        out.writeUTF("[-] 服务端拒绝了您的请求,请检查您是否为服务端白名单用户且账号密码是否输入正确");
                    }
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                try {
                    VerificationUser.closeConnection();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                break;

            } catch (SocketTimeoutException s) {
                System.out.println("Socket timed out!");
                break;
            } catch (IOException e) {
                e.printStackTrace();
                break;
            }
        }
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
            FileUtils.writeLines(filePath, lines);
        }

    }
}
