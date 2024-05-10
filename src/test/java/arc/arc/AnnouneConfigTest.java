package arc.arc;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.swing.tree.TreeNode;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Log4j2
class AnnouneConfigTest {

    static LinkedHashMap<Predicate<Integer>, String> map;


    @BeforeAll
    public static void setup() {
        map = new LinkedHashMap<>();
        map.put(x -> x % 3 == 0, "Fizz");
        map.put(x -> x % 5 == 0, "Buzz");


    }


    @Test
    public void test2() {
        String[] actions = //{"F", "F", "F"};
                new String[100000];
        for (int i = 0; i < actions.length; i++) {
            String s = List.of("F", "L", "R").get(ThreadLocalRandom.current().nextInt(0, 3));
            actions[i] = s;
        }

        int[] positions = new int[actions.length + 1];
        boolean[] directions = new boolean[actions.length + 1];
        directions[0] = true;

        int pos = 0;
        boolean dir = true;
        int i = 0;
        while (i < actions.length) {
            switch (actions[i]) {
                case "F" -> pos = dir ? pos + 1 : pos - 1;
                case "R" -> dir = true;
                case "L" -> dir = false;
            }
            positions[++i] = pos;
            directions[i] = dir;
        }

/*        for(i=0;i<actions.length+1;i++){
            System.out.println(i+": "+ positions[i]+" "+(directions[i] ? "R" : "L"));
        }*/

        Set<Integer> possibilities = new HashSet<>();
        //System.out.println("End: " + positions[positions.length - 1]);

        int forwards = 0;
        int lastPos = positions[positions.length - 1];
        for (i = actions.length - 1; i >= 0; i--) {
            String action = actions[i];
            boolean currentDir = directions[i];
            if (action.equals("F")) {
                forwards++;
                int myDir = (currentDir ? -1 : 1);
                int shifted = lastPos + myDir;
                int p1 = myDir + lastPos;
                int p2 = shifted + (forwards - 1) * myDir * 2;

                //int tf = findEnd(actions, positions, directions, i, "F");
/*                int tl = findEnd(actions, positions, directions, i, "L");
                int tr = findEnd(actions, positions, directions, i, "R");

                System.out.println(p1+" "+p2+" "+tl+" "+tr);*/

                possibilities.add(p1);
                possibilities.add(p2);
            } else {
                boolean nextDirection = action.equals("R");
                boolean weChangeDirection = nextDirection != currentDir;
                int myDir = (currentDir ? 1 : -1);

                int ifForward;
                if (weChangeDirection) ifForward = lastPos + myDir * (forwards * 2 + 1);
                else ifForward = lastPos + myDir;

                int ifDifferentDirection1;
                if (weChangeDirection) ifDifferentDirection1 = lastPos + myDir * (forwards * 2);
                else ifDifferentDirection1 = lastPos - myDir * (forwards * 2);
                possibilities.add(ifDifferentDirection1);
                possibilities.add(ifForward);
/*
                int tf = findEnd(actions, positions, directions, i, "F");
                int tl = findEnd(actions, positions, directions, i, "L");
                int tr = findEnd(actions, positions, directions, i, "R");

                System.out.println(ifForward + " " + ifDifferentDirection1 + " " + tf + " " + tl + " " + tr);
*/

                forwards = 0;
            }
        }
        System.out.println(possibilities.size());
    }


    @Test
    void test3123() {
        Scanner sc = new Scanner(System.in);
        int size = sc.nextInt();
        int queries = sc.nextInt();

        String[] arr = new String[size];
        for (int i = 0; i < size; i++) {
            String s = sc.next();
            arr[i] = s;
        }

        for (int i = 0; i < queries; i++) {
            int idx = sc.nextInt();
            String prefix = sc.next();
            int res = binSearchPrefix(prefix, arr);
            if (res == -1 || res + idx >= size) System.out.println(-1);
            else System.out.println(arr[res + idx]);
        }
    }

    int binSearchPrefix(String prefix, String[] arr) {
        int start = 0;
        int end = arr.length - 1;
        while (end - start > 1) {
            int mid = start + (end - start) / 2;
            String midStr = arr[mid];
            if (midStr.compareTo(prefix) > 0) {
                end = mid - 1;
            } else {
                start = mid + 1;
            }
        }
        if (arr[start].startsWith(prefix)) return start;
        else if (arr[end].startsWith(prefix)) return end;
        return -1;
    }


    @Test
    @SneakyThrows
    public void test() {
        List<Event> events = new ArrayList<>();

        events.sort(Comparator.comparingInt(e -> Math.min(e.start, e.end)));

        Map<Integer, List<Event>> ends = new HashMap<>();

        int good = 0;
        for(Event e : events){
            boolean cross = e.end < e.start;
        }

        int p1 = 0, p2 = 0;
        int clear = 0;
    }

    @AllArgsConstructor
    @Getter
    static class Event {
        int start, end;
    }

    @Test
    public void test123123() {

    }

    @Setter
    @Getter
    static class TrieNode {
        Map<Character, TrieNode> children = new HashMap<>();
        WordData wordData;

        public void addWord(String word, int score, int idx) {
            TrieNode current = this;
            for (char c : word.toCharArray()) {
                current.children.putIfAbsent(c, new TrieNode());
                current = current.children.get(c);
            }
            current.wordData = new WordData(word, score, idx);
        }

        public int topIndex(String prefix) {
            return byPrefix(prefix).stream().max(Comparator.comparingInt(TrieNode.WordData::score)).map(TrieNode.WordData::idx).orElse(-1);
        }

        public record WordData(String word, int score, int idx) {
        }

        public List<WordData> byPrefix(String prefix) {
            TrieNode current = this;
            for (char c : prefix.toCharArray()) {
                TrieNode next = current.children.get(c);
                if (next == null) return new ArrayList<>();
                current = next;
            }
            return current.all();
        }

        public List<WordData> all() {
            List<WordData> res = new ArrayList<>();
            putAll(res);
            return res;
        }

        private void putAll(List<WordData> words) {
            if (wordData != null) words.add(wordData);
            for (var node : children.values()) {
                node.putAll(words);
            }
        }
    }


    String main(int i) {
        return map.entrySet().stream().map(entry -> entry.getKey().test(i) ? entry.getValue() : "").collect(Collectors.joining()).transform(s -> s.isEmpty() ? String.valueOf(i) : s);
    }

    static final String test = """
            0,FizzBuzz
            1,1
            2,2
            3,Fizz
            5,Buzz
            15,FizzBuzz
            30,FizzBuzz
            31,31
            32,32
            33,Fizz
            35,Buzz
            45,FizzBuzz
            46,46
            -5,Buzz
            -3,Fizz
            -15,FizzBuzz
            -30,FizzBuzz
            """;

    @ParameterizedTest
    @CsvSource(textBlock = test)
    public void test(int i, String s) {
        assertEquals(main(i), s);
    }
}