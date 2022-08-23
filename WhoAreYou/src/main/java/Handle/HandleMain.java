package Handle;

import Input.InputMain;
import UACArray.FristAndLastArray;

import java.util.Arrays;
import java.util.Objects;
import java.util.Scanner;

// 处理主函数
public class HandleMain {
    public static int i = 0, x = 0, flag = 0, arrayLength;
    public static Scanner input = new Scanner(System.in);
    public static FristAndLastArray fristAndLastArray = new FristAndLastArray();

    // 逻辑1：    我是蓝队
    public static FristAndLastArray handle_blue() {
        x = 0;
        // 前置位和后置位
        String[] fristArray = new String[4];
        String[] lastArray = new String[4];

        // 我是警察，没查到，然后有人恶意带票，两个结果
        // 1：被投死的是杀手，后置位可疑程度高 4个为前后置位的分割线
        // 2：被投死的不是杀手，前置位可疑程度高
        String[] yourCare = InputMain.input_main("输入你所怀疑的: ", 8);
        if (!Objects.equals(yourCare[0], "null")) {
            arrayLength = yourCare.length;
        } else {
            arrayLength = 0;
        }


        System.out.println("[+] 请输入被投死的是否是杀手：\n1: 杀手\t2: 不是杀手");
        int isKiller = input.nextInt();

        try {
            if (isKiller == 1) {
                // 是杀手
                for (i = 0; i < arrayLength; i++) {
                    if (i > 3) {
                        if (flag == 0) {
                            x = 0;
                            flag = 1;
                        }
                        // 后置位可疑程度高
                        lastArray[x] = yourCare[i];

                    } else {
                        fristArray[x] = yourCare[i];
                    }
                    x++;

                }
                fristAndLastArray.choice_Array = "lastArray";
                fristAndLastArray.fristArray = fristArray;
                fristAndLastArray.lastArray = lastArray;
                fristAndLastArray.yourCare = yourCare;

                return fristAndLastArray;
            } else {
                // 不是杀手
                for (; i < arrayLength; i++) {
                    if (i > 3) {
                        if (flag == 0) {
                            x = 0;
                            flag = 1;
                        }

                        lastArray[x] = yourCare[i];
                        x++;

                    } else {
                        // 前置位可疑程度高
                        fristArray[x] = yourCare[i];
                        x++;
                    }

                }

                fristAndLastArray.choice_Array = "fristArray";
                fristAndLastArray.fristArray = fristArray;
                fristAndLastArray.lastArray = lastArray;
                fristAndLastArray.yourCare = yourCare;

                return fristAndLastArray;
            }
        } catch (Exception e) {

        }
        return null;
    }

    // 逻辑1：    我是红队
    public static FristAndLastArray handle_red() {
        x = 0;
        // 前置位和后置位
        String[] fristArray = new String[4];
        String[] lastArray = new String[4];

        // 我是杀手，有人投了我的队友，两个结果
        // 1：平民乱投的，后置位可疑
        // 2：警察带票，前置位可疑
        String[] yourCare = InputMain.input_main("输入你所怀疑的: ", 8);
        if (!Objects.equals(yourCare[0], "null")) {
            arrayLength = yourCare.length;
        } else {
            arrayLength = 0;
        }

        System.out.println("[+] 是否是警察带票：\n1: 是平民乱投的\t2: 是警察带票的");
        int isKiller = input.nextInt();
        try {
            if (isKiller == 1) {
                for (; i < arrayLength; i++) {
                    if (i > 3) {
                        if (flag == 0) {
                            x = 0;
                            flag = 1;
                        }
                        // 后置位可疑程度高
                        lastArray[x] = yourCare[i];

                    } else {
                        fristArray[x] = yourCare[i];
                    }
                    x++;

                }
                fristAndLastArray.choice_Array = "lastArray";
                fristAndLastArray.fristArray = fristArray;
                fristAndLastArray.lastArray = lastArray;
                fristAndLastArray.yourCare = yourCare;

                return fristAndLastArray;
            } else {
                // 不是杀手
                for (; i < arrayLength; i++) {
                    if (i > 3) {
                        if (flag == 0) {
                            x = 0;
                            flag = 1;
                        }

                        lastArray[x] = yourCare[i];
                        x++;

                    } else {
                        // 前置位可疑程度高
                        fristArray[x] = yourCare[i];
                        x++;
                    }

                }
                fristAndLastArray.choice_Array = "fristArray";
                fristAndLastArray.fristArray = fristArray;
                fristAndLastArray.lastArray = lastArray;
                fristAndLastArray.yourCare = yourCare;

                return fristAndLastArray;
            }

        } catch (Exception e) {

        }

        return null;
    }

    // 逻辑1：    我是平民
    public static FristAndLastArray handle_simple() {
        x = 0;
        // 前置位和后置位
        String[] fristArray = new String[4];
        String[] lastArray = new String[4];

        // 我是平民，投票会有两个结果
        // 1：杀手， 观察末置位，末置位一般无身份
        // 2：被投死的不是杀手，前置位可疑程度高
        String[] yourCare = InputMain.input_main("输入末置位: ", 8);
        if (!Objects.equals(yourCare[0], "null")) {
            arrayLength = yourCare.length;
        } else {
            arrayLength = 0;
        }

        // System.out.println("[+] 请输入被投死的是否是杀手：\n1: 杀手\t2: 不是杀手");
        // int isKiller = input.nextInt();

        // 只记录末置位
        x = 0;
        try {
            for (i = 0; i < arrayLength; i++) {
                if (i > 3) {
                    if (flag == 0) {
                        x = 0;
                        flag = 1;
                    }

                    lastArray[x] = yourCare[i];
                    x++;

                } else {

                    fristArray[x] = yourCare[i];
                    x++;
                }

            }
        } catch (Exception e) {

        }


        fristAndLastArray.choice_Array = "lastSet";
        fristAndLastArray.fristArray = fristArray;
        fristAndLastArray.lastArray = lastArray;
        fristAndLastArray.yourCare = yourCare;

        return fristAndLastArray;

    }

    // 排除算法
    public static int[] exclude(FristAndLastArray fristAndLastArray, String[] yourFriend, int[] hasDie, String[] middle) {
        int[] excludeArray = new int[18];
        int x2 = 0;

        try {
            for (int x1 = 1; x1 < 19; x1++) { // Arrays.toString(middle).contains(String.valueOf(x1)) ||
                if (fristAndLastArray == null) {
                    if (Arrays.toString(middle).contains(String.valueOf(x1)) || Arrays.toString(hasDie).contains(String.valueOf(x1)) || Arrays.toString(yourFriend).contains(String.valueOf(x1))) {
                        continue;
                    } else {
                        excludeArray[x2] = x1;
                        x2++;
                    }
                } else if (Arrays.toString(middle).contains(String.valueOf(x1)) || Arrays.toString(hasDie).contains(String.valueOf(x1)) || Arrays.toString(yourFriend).contains(String.valueOf(x1)) || Arrays.toString(fristAndLastArray.yourCare).contains(String.valueOf(x1))) {
                    continue;
                } else {
                    excludeArray[x2] = x1;
                    x2++;
                }

            }
        } catch (Exception e) {

        }


        return excludeArray;
    }

    // 排除已经死亡的人
    public static int[] excludeDie() {
        int[] hasDie = new int[15];
        i = 0;

        System.out.println("[+] 请输入已经死亡的人物:");
        String x = input.next();
        while (!x.equals("0")) {
            hasDie[i] = Integer.parseInt(x);
            i++;
            x = input.next();
            if (i == 15) {
                break;
            }
        }

        return hasDie;
    }
}
