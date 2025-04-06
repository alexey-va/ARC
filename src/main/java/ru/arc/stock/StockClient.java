package ru.arc.stock;

import ru.arc.util.Common;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Log4j2
@SuppressWarnings({"unchecked", "rawtypes"})
public class StockClient {

    private final String FINN_API_KEY;
    private final String POLY_API_KEY;

    static WebSocketClient webSocketClient;
    static volatile boolean isClosed = true;

    static Map<String, List<Double>> prices = new ConcurrentHashMap<>();

    private static final Gson gson = Common.gson;
    private static final ExecutorService service = Executors.newSingleThreadExecutor();

    public Map<String, Double> cryptoPrices() {
        String ids = StockMarket.configStocks().stream()
                .filter(stock -> stock.type == Stock.Type.CRYPTO)
                .map(ConfigStock::getSymbol)
                .collect(Collectors.joining("%2C"));
        String url = "https://api.coingecko.com/api/v3/simple/price?ids=" + ids + "&vs_currencies=usd&precision=full";
        log.debug("Fetching crypto prices from {}", url);
        try {
            HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            InputStream stream = connection.getInputStream();

            TypeToken<Map<String, Map<String, Double>>> typeToken = new TypeToken<>() {
            };
            Reader reader = new InputStreamReader(stream);
            Map<String, Map<String, Double>> map = gson.fromJson(reader, typeToken);

            Map<String, Double> prices = new HashMap<>();
            map.forEach((key, value) -> prices.put(key, value.get("usd")));
            log.debug("Fetched crypto prices: {}", prices);
            return prices;
        } catch (Exception e) {
            log.error("Could not load crypto prices", e);
            return Map.of();
        }
    }

    private static double fetchInvesting(String url) {
        try {
            Connection.Response response = Jsoup.connect(url).execute();
            if (response.statusCode() < 200 || response.statusCode() > 299) {
                // print reason for bad response
                System.out.println("Response code: " + response.statusCode());
                System.out.println(response.headers());
                System.out.println();
                System.out.println(response.body());
                return -1;
            }
            Document document = response.parse();
            Element divElement = document.select("div[data-test=instrument-price-last]").first();
            if (divElement == null) return -1;
            return Double.parseDouble(divElement.text()
                    .replace(".", "")
                    .replace(",", "."));
        } catch (Exception e) {
            log.error("Could not load price from investing.com", e);
            return -1;
        }
    }


    public void startWebSocket(Collection<String> symbols) {
        try {
            MySocket client = new MySocket();
            webSocketClient = new WebSocketClient();

            final String SERVER_URI = "wss://ws.finnhub.io?token=cn7rbt9r01qplv1e8j50cn7rbt9r01qplv1e8j5g";
            final int TIMEOUT_SECONDS = 5;

            QueuedThreadPool threadPool = new QueuedThreadPool(30, 1, 60000);
            webSocketClient.setExecutor(threadPool);
            webSocketClient.start();
            webSocketClient.connect(client, new URI(SERVER_URI));

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
                                log.error("Could not send message to server", e);
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

    private Double getStockPrice(ConfigStock stock) {
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

    private Double getCurrencyPrice(ConfigStock stock) {
        String url = "https://ru.investing.com/currencies/" + stock.symbol.replace("/", "-").toLowerCase();
        return fetchInvesting(url);
    }

    private Double getCommodityPrice(ConfigStock stock) {
        String url = "https://ru.investing.com/commodities/" + stock.symbol.replace("/", "-").toLowerCase();
        return fetchInvesting(url);
    }

    public Double price(ConfigStock stock) {
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
            URL url = URI.create(builder.toString()).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            InputStream stream = connection.getInputStream();

            Map map = gson.fromJson(new InputStreamReader(stream), Map.class);
            return (double) (map.get("c"));
        } catch (Exception e) {
            //e.printStackTrace();
            log.error("Could not load price from finnhub for {}", symbol, e);
            return -1;
        }
    }

    public double dividend(String symbol) {
        if (POLY_API_KEY == null) return 0;
        StringBuilder builder = new StringBuilder("https://api.polygon.io/v3/reference/dividends?");
        builder.append("ticker=").append(symbol);
        builder.append("&apiKey=").append(POLY_API_KEY);

        try {
            URL url = URI.create(builder.toString()).toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            InputStream stream = connection.getInputStream();

            Map map = gson.fromJson(new InputStreamReader(stream), Map.class);
            //System.out.println(map);
            return (double) ((Map<String, Object>) ((List<Object>) map.get("results")).getFirst()).get("cash_amount");
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
                Map map = gson.fromJson(message, Map.class);
                double price = ((Number) ((Map) ((List) map.get("data")).getFirst()).get("p")).doubleValue();
                String symbol = ((String) ((Map) ((List) map.get("data")).getFirst()).get("s"));
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
