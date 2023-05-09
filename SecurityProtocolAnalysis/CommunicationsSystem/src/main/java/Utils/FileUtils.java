package Utils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class FileUtils {

    /**
     * 从文件中按行全部读入List<String>中
     */
    public static List<String> readLines(String filepath, String charsetName) {
        File file = new File(filepath);
        if (!file.exists()) {
            throw new IllegalArgumentException("[Waring] [org.sec.utils.FileUtils] File Not Exist: " + filepath);
        }

        InputStream in = null;
        Reader reader = null;
        BufferedReader bufferReader = null;

        try {
            in = Files.newInputStream(file.toPath());
            reader = new InputStreamReader(in, charsetName);
            bufferReader = new BufferedReader(reader);

            List<String> list = new ArrayList<>();
            String line;
            while ((line = bufferReader.readLine()) != null) {
                list.add(line);
            }
            return list;
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            IOUtils.closeQuietly(bufferReader);
            IOUtils.closeQuietly(reader);
            IOUtils.closeQuietly(in);
        }

        return null;
    }

    /**
     * 将多行写到文件,再次使用该函数会清空文件中的内容
     */
    public static void writeLines(String filepath, List<String> lines, boolean append) {
        if (lines == null || lines.size() < 1) return;

        File file = new File(filepath);
        File dirFile = file.getParentFile();
        mkdir(dirFile);

        OutputStream out = null;
        Writer writer = null;
        BufferedWriter bufferedWriter = null;

        try {
            if (append) {
                out = new FileOutputStream(file, true);
            } else {
                out = new FileOutputStream(file);
            }
            writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
            bufferedWriter = new BufferedWriter(writer);

            for (String line : lines) {
                bufferedWriter.write(line);
                bufferedWriter.newLine();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            IOUtils.closeQuietly(bufferedWriter);
            IOUtils.closeQuietly(writer);
            IOUtils.closeQuietly(out);
        }
    }

    /**
     * 写入Bytes
     */
    public static void writeBytes(String filepath, byte[] bytes) {
        File file = new File(filepath);
        File dirFile = file.getParentFile();
        mkdir(dirFile);

        try (OutputStream out = new FileOutputStream(filepath);
             BufferedOutputStream buff = new BufferedOutputStream(out)) {
            buff.write(bytes);
            buff.flush();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 创建目录
     */
    public static void mkdir(File dirFile) {
        boolean file_exists = dirFile.exists();

        if (file_exists && dirFile.isDirectory()) {
            return;
        }

        if (file_exists && dirFile.isFile()) {
            throw new RuntimeException("[Waring] [org.sec.utils.FileUtils] Not A Directory: " + dirFile);
        }

        if (!file_exists) {
            boolean flag = dirFile.mkdirs();
            assert flag : "[-] Create Directory Failed: " + dirFile.getAbsolutePath();
        }
    }
}
