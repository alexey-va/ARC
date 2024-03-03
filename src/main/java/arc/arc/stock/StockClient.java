package arc.arc.stock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import lombok.RequiredArgsConstructor;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class StockClient {

    private final String FINN_API_KEY;
    private final String POLY_API_KEY;

    static WebSocketClient webSocketClient;
    static volatile boolean isClosed = true;

    static Map<String, List<Double>> prices = new ConcurrentHashMap<>();

    public Map<String, Double> cryptoPrices() {
        String ids = StockMarket.stocks().stream()
                .filter(stock -> stock.type == Stock.Type.CRYPTO)
                .map(Stock::getSymbol)
                .collect(Collectors.joining("%2C"));
        String url = "https://api.coingecko.com/api/v3/simple/price?ids=" + ids + "&vs_currencies=usd&precision=full";
        //System.out.println("URL: "+url);
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            InputStream stream = connection.getInputStream();
            ObjectMapper objectMapper = new ObjectMapper();

            TypeFactory typeFactory = objectMapper.getTypeFactory();
            MapType mapType = typeFactory.constructMapType(
                    ConcurrentHashMap.class,
                    typeFactory.constructType(String.class),
                    typeFactory.constructMapType(HashMap.class, String.class, Object.class)
            );
            Map<String, Map<String, Double>> map = objectMapper.readValue(stream, mapType);

            Map<String, Double> prices = new HashMap<>();
            map.forEach((key, value) -> prices.put(key, value.get("usd")));
            return prices;
        } catch (Exception e) {
            System.out.println("Could not load price for crypto!");
            return Map.of();
        }
    }

    private static double fetchInvesting(String url) {
        try {
            Document document = Jsoup.connect(url).get();
            Element divElement = document.select("div[data-test=instrument-price-last]").first();
            return Double.parseDouble(divElement.text()
                    .replace(".", "")
                    .replace(",", "."));
        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }


    public void startWebSocket(Collection<String> symbols) {
        try {
            MySocket client = new MySocket();
            webSocketClient = new WebSocketClient();

            final String SERVER_URI = "wss://ws.finnhub.io?token=cn7rbt9r01qplv1e8j50cn7rbt9r01qplv1e8j5g";
            final int TIMEOUT_SECONDS = 5;

            webSocketClient.start();
            webSocketClient.connect(client, new URI(SERVER_URI));

            ExecutorService service = Executors.newSingleThreadExecutor();
            isClosed = false;
            service.submit(() -> {
                try {
                    if (client.latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                        // Send a message to the server
                        symbols.forEach(s -> {
                            try {
                                client.getSession().getRemote().sendString("{\"type\":\"subscribe\",\"symbol\":\"" + s + "\"}");
                                Thread.sleep(100);
                            } catch (Exception e) {
                                e.printStackTrace();
                                throw new RuntimeException(e);
                            }
                        });
                        // Close the WebSocket connection
                    } else {
                        isClosed = true;
                        System.err.println("WebSocket connection could not be established within the timeout.");
                    }
                } catch (Exception e) {
                    isClosed = true;
                    //e.printStackTrace();
                }
            });
        } catch (Exception e) {
            isClosed = true;
            //e.printStackTrace();
        }
    }

    public static void stopClient() {
        if (webSocketClient != null && !isClosed) {
            try {
                webSocketClient.stop();
                isClosed = true;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    private Double getStockPrice(Stock stock) {
        if (webSocketClient == null || !webSocketClient.isRunning() || isClosed) {
            System.out.println("Websocket is closed. Starting...");
            startWebSocket(StockMarket.stocks().stream()
                    .filter(st -> st.type == Stock.Type.STOCK)
                    .map(Stock::getSymbol)
                    .toList());
        }
        var list = prices.remove(stock.symbol);
        if (list == null) return fetchFinnhub(stock.symbol);
        OptionalDouble optional = list.stream().mapToDouble(Double::doubleValue).average();
        if (optional.isEmpty()) return fetchFinnhub(stock.symbol);
        return optional.getAsDouble();
    }

    private Double getCurrencyPrice(Stock stock) {
        String url = "https://ru.investing.com/currencies/" + stock.symbol.replace("/", "-").toLowerCase();
        return fetchInvesting(url);
    }

    private Double getCommodityPrice(Stock stock) {
        String url = "https://ru.investing.com/commodities/" + stock.symbol.replace("/", "-").toLowerCase();
        return fetchInvesting(url);
    }

    public Double price(Stock stock) {
        return switch (stock.type) {
            case STOCK -> getStockPrice(stock);
            case CURRENCY -> getCurrencyPrice(stock);
            case COMMODITY -> getCommodityPrice(stock);
            default -> -1.0;
        };
    }

    private double fetchFinnhub(String symbol) {
        if (POLY_API_KEY == null) return -1;
        StringBuilder builder = new StringBuilder("https://finnhub.io/api/v1/quote?");
        builder.append("symbol=").append(symbol);
        builder.append("&token=").append(FINN_API_KEY);

        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(builder.toString()).openConnection();
            InputStream stream = connection.getInputStream();
            //BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            //String received = reader.readLine();

            Map map = new ObjectMapper().readValue(stream, Map.class);
            //System.out.println(map);
            return (double) (map.get("c"));
        } catch (Exception e) {
            //e.printStackTrace();
            System.out.println("Could not load price for  " + symbol);
            return -1;
        }
    }

    public double dividend(String symbol) {
        if (POLY_API_KEY == null) return 0;
        StringBuilder builder = new StringBuilder("https://api.polygon.io/v3/reference/dividends?");
        builder.append("ticker=").append(symbol);
        builder.append("&apiKey=").append(POLY_API_KEY);

        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(builder.toString()).openConnection();
            InputStream stream = connection.getInputStream();
            //BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
            //String received = reader.readLine();

            Map map = new ObjectMapper().readValue(stream, Map.class);
            //System.out.println(map);
            return (double) ((Map<String, Object>) ((List<Object>) map.get("results")).get(0)).get("cash_amount");
        } catch (Exception e) {
            //e.printStackTrace();
            return 0;
        }
    }

    public static class MySocket extends WebSocketAdapter {
        private final CountDownLatch latch = new CountDownLatch(1);

        @Override
        public void onWebSocketConnect(Session sess) {
            super.onWebSocketConnect(sess);
            //System.out.println("WebSocket Connected: " + sess);
            latch.countDown(); // Notify that the connection has been established
            //isClosed = false;
        }

        @Override
        public void onWebSocketText(String message) {
            super.onWebSocketText(message);
            //System.out.println("Received message from server: " + message);
            try {
                Map map = new ObjectMapper().readValue(message, Map.class);
                double price = ((Number) ((Map) ((List) map.get("data")).get(0)).get("p")).doubleValue();
                String symbol = ((String) ((Map) ((List) map.get("data")).get(0)).get("s"));
                prices.putIfAbsent(symbol, new ArrayList<>());
                prices.get(symbol).add(price);
                //System.out.println(price+" "+symbol+" "+Thread.currentThread().getName());
            } catch (Exception e) {
                //System.out.println("Could not read data from stock socket!");
            }
        }

        @Override
        public void onWebSocketClose(int statusCode, String reason) {
            super.onWebSocketClose(statusCode, reason);
            System.out.println("WebSocket Closed: " + statusCode + " - " + reason);
            isClosed = true;
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            super.onWebSocketError(cause);
            //cause.printStackTrace();
        }
    }

}
