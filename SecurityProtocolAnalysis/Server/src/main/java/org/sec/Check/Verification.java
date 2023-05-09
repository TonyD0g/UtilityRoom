package org.sec.Check;

import javafx.scene.control.TextArea;
import org.sec.Controller.ServerController;
import org.sec.Main;

import java.lang.reflect.Field;
import java.sql.*;

/**
 * 验证客户端是否为服务端数据库中的,如果是则正常连接否则抛弃
 */
public class Verification {
    public static String url = "jdbc:mysql://127.0.0.1:3306/CommunicationsSystem?useUnicode=true&characterEncoding=utf-8&useSSL=false";
    public static String mysqlUsername = "6WIZiNw3mI7QJljj";
    public static String mysqlPassword = "4GzoqKF1vwqsHndT";
    public static Connection connection;

    public static void main(String[] args) throws SQLException {
        openConnection();
        closeConnection();
    }

    public static Object getField(String fieldName) throws ClassNotFoundException, NoSuchFieldException, InstantiationException, IllegalAccessException {
        Class clazz = Class.forName("org.sec.Controller.ServerController");
        Field field =  clazz.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(Main.controller);
    }

    public static void deleteUser(String username) throws SQLException, NoSuchFieldException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        String sql1 = "DELETE FROM user where username = ?;";
        PreparedStatement preparedStatement = connection.prepareStatement(sql1);
        preparedStatement.setString(1, username);
        try {
            preparedStatement.executeUpdate();
        } catch (Exception e) {
            TextArea print = (TextArea) getField("print");
            print.appendText("[-] 删除失败,请检查该username是否存在");
        }
        preparedStatement.close();
    }

    public static void addUser(String username, String password) throws SQLException, NoSuchFieldException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        String sql1 = "INSERT INTO user VALUES(?, ?, ?);";
        String sql2 = "select COUNT(*) FROM user ";
        int totalLine = 0;

        PreparedStatement preparedStatement = connection.prepareStatement(sql2);
        ResultSet resultSet = preparedStatement.executeQuery();

        while (resultSet.next()) {
            totalLine = resultSet.getInt(1);
        }

        preparedStatement = connection.prepareStatement(sql1);
        preparedStatement.setString(1, String.valueOf(totalLine + 1));
        preparedStatement.setString(2, username);
        preparedStatement.setString(3, password);
        try {
            preparedStatement.executeUpdate();
        } catch (Exception e) {
            TextArea print = (TextArea) getField("print");
            print.appendText("[-] 该用户已存在,请创建一个新用户");
        }
        preparedStatement.close();
    }

    public static boolean checkUserAndPass(String username, String password) throws SQLException, NoSuchFieldException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        createUserTable();
        String sql1 = "select password from user where username = ?";
        PreparedStatement preparedStatement = connection.prepareStatement(sql1);
        preparedStatement.setString(1, username);

        ResultSet outcome = preparedStatement.executeQuery();
        while (outcome.next()) {
            if (password.equals(outcome.getString(1))) {
                TextArea print = (TextArea) getField("print");
                print.appendText("[+] 验证成功,为数据库中的用户,可以开始正常通讯");
                return true;
            } else {
                TextArea print = (TextArea) getField("print");
                print.appendText("[-] 验证失败,不为数据库中的用户,不可以通讯");
                return false;
            }
        }
        preparedStatement.close();
        return false;
    }

    /**
     * 获取被锁定ip最后登录的时间
     */
    public static String getTime(String ip) throws SQLException {
        String sql2 = "select time from safe where ip = ?;";
        String outcome = null;
        PreparedStatement preparedStatement = connection.prepareStatement(sql2);
        preparedStatement.setString(1, ip);
        ResultSet resultSet = preparedStatement.executeQuery();
        while (resultSet.next()) {
            outcome = resultSet.getString(1);
        }
        preparedStatement.close();
        return outcome;
    }

    /**
     * 改变时间:当IP被锁定后还继续破解则刷新最后破解的时间,从这个时候开始等十分钟
     */
    public static void changeTime(String ip, String time) throws SQLException {
        String sql2 = "UPDATE safe SET time = ? where ip = ?;";
        PreparedStatement preparedStatement = connection.prepareStatement(sql2);
        preparedStatement.setString(1, time);
        preparedStatement.setString(2, ip);
        preparedStatement.executeUpdate();
        preparedStatement.close();
    }

    /**
     * 登录失败时,登录失败次数+1
     */
    public static void addNumber(String ip) throws SQLException {
        String sql2 = "UPDATE safe SET number = ? where ip = ?;";
        int number = checkNumber(ip);

        PreparedStatement preparedStatement = connection.prepareStatement(sql2);
        preparedStatement.setInt(1, number + 1);
        preparedStatement.setString(2, ip);
        preparedStatement.executeUpdate();
        preparedStatement.close();
    }

    /**
     * 检查是否超过阈值,超过则代表着可能存在暴力破解
     */
    public static int checkNumber(String ip) throws SQLException {
        String sql2 = "select number FROM safe where ip = ?;";
        int number = 0;

        PreparedStatement preparedStatement = connection.prepareStatement(sql2);
        preparedStatement.setString(1, ip);
        ResultSet resultSet = preparedStatement.executeQuery();

        while (resultSet.next()) {
            number = resultSet.getInt(1);
        }
        preparedStatement.close();
        return number;
    }

    public static void deleteIp(String ip) throws SQLException, NoSuchFieldException, ClassNotFoundException, InstantiationException, IllegalAccessException {
        String sql1 = "DELETE FROM safe where ip = ?;";
        PreparedStatement preparedStatement = connection.prepareStatement(sql1);
        preparedStatement.setString(1, ip);
        try {
            preparedStatement.executeUpdate();
        } catch (Exception e) {
            TextArea print = (TextArea) getField("print");
            print.appendText("[-] 删除IP失败,请检查该IP是否存在");
        }
        preparedStatement.close();
    }

    /**
     * 添加登录失败的ip
     */
    public static void addIp(String ip, String time) throws SQLException {
        // 插入前检查是否已经存在,已存在则不插入
        String sql2 = "select count(*) from safe where ip = ?;";
        int number = 0;
        PreparedStatement preparedStatement = connection.prepareStatement(sql2);
        preparedStatement.setString(1, ip);
        ResultSet resultSet = preparedStatement.executeQuery();
        while (resultSet.next()) {
            number = resultSet.getInt(1);
        }
        if (number < 1) {
            String sql1 = "INSERT INTO safe VALUES(?, ?, ?);";
            number = checkNumber(ip);

            preparedStatement = connection.prepareStatement(sql1);
            preparedStatement.setString(1, ip);
            preparedStatement.setInt(2, number + 1);
            preparedStatement.setString(3, time);
            try {
                preparedStatement.executeUpdate();
            } catch (Exception ignored) {
            }
        }
        preparedStatement.close();
    }

    public static void createSafeTable() throws SQLException {
        Statement statement = connection.createStatement();
        String sql1 = "create table  if not exists safe(" +
                "ip varchar(20) primary key," +
                "number int unique," +
                "time varchar(20))";
        statement.execute(sql1);
        statement.close();
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
