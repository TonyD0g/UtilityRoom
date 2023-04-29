package org.sec.Check;

import java.sql.*;

/**
 * 验证客户端是否为服务端数据库中的,如果是则正常连接否则抛弃
 */
public class VerificationUser {
    public static String url = "jdbc:mysql://127.0.0.1:3306/CommunicationsSystem?useUnicode=true&characterEncoding=utf-8&useSSL=false";
    public static String mysqlUsername = "6WIZiNw3mI7QJljj";
    public static String mysqlPassword = "4GzoqKF1vwqsHndT";
    public static Connection connection;

    public static boolean checkUserAndPass(String username, String password) throws SQLException {
        createUserTable();
        String sql1 = "select password from user where username = ?";
        PreparedStatement preparedStatement = connection.prepareStatement(sql1);
        preparedStatement.setString(1, username);

        ResultSet outcome = preparedStatement.executeQuery();
        while (outcome.next()){
            if(password.equals(outcome.getString(1))){
                System.out.println("[+] 验证成功,为数据库中的用户,可以开始正常通讯");
                return true;
            }else {
                System.out.println("[-] 验证失败,不为数据库中的用户,不可以通讯");
                return false;
            }
        }
        return false;
    }

    public static void createUserTable() throws SQLException {

        Statement statement = connection.createStatement();
        String sql1 = "use CommunicationsSystem";
        String sql2 = "create table  if not exists user(" +
                "id int(5) primary key auto_increment," +
                "username varchar(20) unique," +
                "password varchar(20))";
        statement.execute(sql1);
        statement.execute(sql2);
        statement.close();
    }

    public static void openConnection() {
        try {
            Class.forName("com.mysql.jdbc.Driver");
            connection = DriverManager.getConnection(url, mysqlUsername, mysqlPassword);
        } catch (ClassNotFoundException | SQLException e) {
            throw new RuntimeException(e);
        }
    }

    public static void closeConnection() throws SQLException {
        connection.close();
    }
}
