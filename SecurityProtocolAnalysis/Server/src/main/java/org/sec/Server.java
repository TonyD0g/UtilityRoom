package org.sec;

import javafx.application.Platform;
import javafx.scene.control.TextArea;
import org.sec.Check.Verification;
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
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.sec.Check.Verification.getField;

public class Server extends Thread implements Runnable {
    public static String input;
    public static List<String> ServerKey;
    public static String clientPublicKey;
    public static TextArea print = null;
    public static DataInputStream in;
    public static DataOutputStream out;

    public static void connect(int port) {
        try {
            Thread t = new Server(port);
            createKey(".\\ServerKey.txt"); // 查看是否已经生成公私钥,没有则生成
            t.run();
        }  catch (Exception e) {
            e.printStackTrace();
        }
    }

    private ServerSocket serverSocket;

    public Server(int port) throws IOException {
        serverSocket = new ServerSocket(port);
        serverSocket.setSoTimeout(1000000000);
    }

    public void run() {
        while (true) {
            Socket server;
            try {
                server = serverSocket.accept();
                print = (TextArea) getField("print");
                print.appendText("等待远程连接，端口号为：" + serverSocket.getLocalPort() + "..." + "\n");
                String inetAddress = String.valueOf(server.getInetAddress());
                inetAddress = inetAddress.substring(1);
                TextArea finalPrint = print;
                Platform.runLater(() -> finalPrint.appendText("远程主机地址：" + server.getRemoteSocketAddress() + "\n"));

                in = new DataInputStream(server.getInputStream());
                out = new DataOutputStream(server.getOutputStream());
                if (safeDefender(inetAddress)) {
                    out.writeUTF("[-] 警告,请不要暴力破解!或者请您好好想想密码,请十分钟后再试\n");
                    break;
                }
                out.writeUTF("[+] 你已连接远程服务器,远程服务器正在检测资格: " + server.getLocalSocketAddress() + "\n"); // 发送信息给Client
                ServerKey = FileUtils.readLines(".\\ServerKey.txt", String.valueOf(StandardCharsets.UTF_8));

                // 发送服务器公钥给客户端,用于给客户端去加密数据
                if (ServerKey != null) {
                    out.writeUTF(ServerKey.get(0));
                }
                communication(inetAddress, ServerKey, server);
                try {
                    Verification.closeConnection();
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
                break;

            } catch (SocketTimeoutException s) {
                if (print != null) {
                    print.appendText("Socket timed out!");
                }
                break;
            } catch (IOException | SQLException e) {
                break;
            } catch (ParseException | IllegalAccessException | InstantiationException | ClassNotFoundException |
                     NoSuchFieldException e) {
                e.printStackTrace();
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
            FileUtils.writeLines(filePath, lines, false);
        }

    }

    /**
     * 通讯模块
     */
    public void communication(String inetAddress, List<String> ServerKey, Socket server) throws IOException, NoSuchFieldException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        TextArea print = (TextArea) getField("print");
        // 读取账号密码,并查看服务端数据库是否存在该用户,存在则继续,否则结束
        Verification.openConnection();
        try {
            input = in.readUTF();
            String[] userInfo = stringUtils.splitBySymbol(RSAUtil.decrypt(input, ServerKey.get(1)), " ");
            if (Verification.checkUserAndPass(userInfo[0], userInfo[1])) {
                Verification.deleteIp(inetAddress); // 验证成功,即把登录失败IP名单中的该项删除z
                out.writeUTF("\n[+] 服务端接受请求,正在建立通讯连接~" + "\n");
                out.flush();
                clientPublicKey = RSAUtil.decrypt(in.readUTF(), ServerKey.get(1));   // 读取客户端的公钥

                Platform.runLater(() -> print.appendText("\n-------------------------------------\n服务端:" + "\n"));

                while (true) {
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    try {
                        input = in.readUTF();
                        String decryptText = null;
                        try {
                            decryptText = RSAUtil.decrypt(input, ServerKey.get(1));
                        } catch (Exception ignored) {

                        }
                        String finalDecryptText = decryptText;
                        //System.out.println(finalDecryptText);
                        Platform.runLater(() -> print.appendText("[+] " + thisTime() + " 客户端发来消息: " + finalDecryptText + "\n"));
                        // 将数据回传给Client端
                        try {
                            out.writeUTF(RSAUtil.encrypt(decryptText, clientPublicKey));
                        } catch (Exception ignored) {
                        }
                    } catch (IOException ignored) {

                    }

                }

            } else {
                Verification.addIp(inetAddress, thisTime()); // 登录失败时,将此IP记录到数据库中
                Verification.addNumber(inetAddress); // 登录失败时,登录失败次数+1
                out.writeUTF("[-] 服务端拒绝了您的请求,请检查您是否为服务端白名单用户且账号密码是否输入正确" + "\n");
            }
        } catch (SQLException e) {
            Platform.runLater(() -> print.appendText("\n-------------------------------------" + "\n"));
            server.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 防止暴力破解
     */
    public static boolean safeDefender(String inetAddress) throws IOException, SQLException, ParseException, NoSuchFieldException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        Verification.openConnection();

        String blockTime = Verification.getTime(inetAddress);
        if (blockTime != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            String unlockTime = changeTime(sdf.parse(blockTime), 10);
            // 如果此时的时间大于可以解封的时间,则解封该Ip
            if (sdf.parse(thisTime()).compareTo(sdf.parse(unlockTime)) > 0) {
                Verification.deleteIp(inetAddress);
            }
        }

        List<String> lines = new ArrayList<>();
        // 如果一个ip连接失败3次,就暂缓该Ip的连接

        // 创建登录日志,方便溯源
        File log = new File(".\\log.txt");
        if (!log.exists()) {
            log.createNewFile();
        }
        String time = thisTime();
        lines.add(time + " " + inetAddress);
        FileUtils.writeLines(".\\log.txt", lines, true);

        // 将 ip 次数 时间 记录到数据库中
        Verification.createSafeTable();
        int number = Verification.checkNumber(inetAddress);
        // 登录失败超过3次,则禁止连接
        if (number > 3) {
            Verification.changeTime(inetAddress, time);
            return true;
        }
        Verification.closeConnection();
        return false;
    }

    /**
     * 返回当前时间(yyyy-MM-dd HH:mm:ss)
     */
    public static String thisTime() {
        Date date = new Date();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
        return dateFormat.format(date);
    }

    /**
     * 改变时间,如让时间加10分钟
     */
    public static String changeTime(Date thisTime, long minutes) {
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        minutes = minutes * 60 * 1000;
        Date afterDate = new Date(thisTime.getTime() + minutes);//30分钟后的时间
        return dateFormat.format(afterDate);
    }
}
