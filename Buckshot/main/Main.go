package main

import (
	"bufio"
	"fmt"
	"os"
	"regexp"
	"strconv"
	"strings"
)

import (
	"errors"
)

// Queue 表示一个简单的队列
type Queue struct {
	items []interface{}
}

// NewQueue 创建一个新的队列
func NewQueue() *Queue {
	return &Queue{
		items: []interface{}{},
	}
}

// Enqueue 入队操作
func (q *Queue) Enqueue(item interface{}) {
	q.items = append(q.items, item)
}

// Dequeue 出队操作
func (q *Queue) Dequeue() (interface{}, error) {
	if q.IsEmpty() {
		return nil, errors.New("queue is empty")
	}
	// 获取队首元素
	item := q.items[0]
	// 将队列的元素左移
	q.items = q.items[1:]
	return item, nil
}

// IsEmpty 判断队列是否为空
func (q *Queue) IsEmpty() bool {
	return len(q.items) == 0
}

// Size 返回队列的大小
func (q *Queue) Size() int {
	return len(q.items)
}

// Peek 查看队首元素但不出队
func (q *Queue) Peek() (interface{}, error) {
	if q.IsEmpty() {
		return nil, errors.New("queue is empty")
	}
	return q.items[0], nil
}

// Display 显示队列的内容
func (q *Queue) Display() {
	fmt.Print(q.items)
	fmt.Println(" ")
}

// SetAt 修改指定索引处的元素
func (q *Queue) SetAt(index int, item interface{}) error {
	if index < 0 || index >= len(q.items) {
		return errors.New("index out of bounds")
	}
	q.items[index] = item
	return nil
}

func main() {
	fmt.Println("[+] Version: 0.3 By TonyD0g")
	for {
		isBreak := false
		queue := NewQueue()
		fmt.Println("\n\n[+] 新的一轮子弹上膛")
		fmt.Println("[+] 请输入实弹/虚弹的比例,按空格进行划分,如:\t2 3 \n则实弹为2,虚弹为3\n----------------------------------------------")
		fmt.Printf("实弹 虚弹:")
		originReader, err := bufio.NewReader(os.Stdin).ReadString('\n')
		if err != nil {
			fmt.Println("[-] 读取输入时发生错误:", err)
			return
		}
		bullets := strings.Fields(originReader)
		liveShell, err := strconv.Atoi(bullets[0]) // 实弹
		if err != nil {
			fmt.Println(err)
			continue
		}
		blankShell, otherErr := strconv.Atoi(bullets[1]) // 虚弹
		if otherErr != nil {
			fmt.Println(otherErr)
			continue
		}

		initShellIndex(queue, liveShell+blankShell)
		fmt.Print("[+] 子弹序列为:\t")
		queue.Display()

		for {
			fmt.Println("[+] 输入1实弹减1,输入2虚弹减1,输入3子弹逆转,输入4记录子弹,输入5表示进行下一轮")
			originReader, err = bufio.NewReader(os.Stdin).ReadString('\n')
			originReader = regexp.MustCompile(`\s+`).ReplaceAllString(originReader, "")
			switch originReader {
			case "1":
				if liveShell > 0 {
					liveShell = liveShell - 1
					_, _ = queue.Dequeue()
				}
				break
			case "2":
				if blankShell > 0 {
					blankShell = blankShell - 1
					_, _ = queue.Dequeue()
				}
				break
			case "3":
				fmt.Println("[+] 有人使用了子弹逆转! 请观察是实弹还是虚弹,实弹输入1,虚弹输入2")
				originReader, err = bufio.NewReader(os.Stdin).ReadString('\n')
				originReader = regexp.MustCompile(`\s+`).ReplaceAllString(originReader, "")
				if originReader == "1" && blankShell > 0 {
					blankShell = blankShell - 1
					_, _ = queue.Dequeue()
				} else if originReader == "2" && liveShell > 0 {
					liveShell = liveShell - 1
					_, _ = queue.Dequeue()
				}
				break
			case "4":
				fmt.Println("[+] 记录子弹,输入例子:5 1 则代表第五个子弹为实弹,输入3 0 则代表第三个子弹为虚弹")
				originReader, err = bufio.NewReader(os.Stdin).ReadString('\n')
				inputData := strings.Fields(originReader)
				pos, _ := strconv.Atoi(inputData[0]) // 获取子弹下标
				if inputData[1] == "1" {
					queue.SetAt(pos-1, "1")
				} else {
					queue.SetAt(pos-1, "0")
				}
				fmt.Print("[+] 子弹序列为:\t")
				queue.Display()
				break
			case "5":
				isBreak = true
				break
			}
			if liveShell < 0 {
				liveShell = 0
			}
			if blankShell < 0 {
				blankShell = 0
			}
			if isBreak {
				break
			}

			fmt.Println(fmt.Sprintf("[+] 现在实弹/虚弹比例为: %s/%s", strconv.Itoa(liveShell), strconv.Itoa(blankShell)))
			fmt.Print("[+] 子弹序列为:\t")
			queue.Display()
			if liveShell == 0 && blankShell == 0 {
				break
			}
		}
		fmt.Println("----------------------------------------------")
	}
}

func initShellIndex(queue *Queue, allShellLen int) {
	for i := 0; i < allShellLen; i++ {
		queue.Enqueue("?")
	}
}
