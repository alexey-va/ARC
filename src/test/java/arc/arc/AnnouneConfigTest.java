package arc.arc;

import lombok.extern.log4j.Log4j2;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.LinkedHashMap;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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

    String main(int i) {
        return map.entrySet().stream()
                .map(entry -> entry.getKey().test(i) ? entry.getValue() : "")
                .collect(Collectors.joining())
                .transform(s -> s.isEmpty() ? String.valueOf(i) : s);
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
        assertEquals(main(i),s);
    }
}