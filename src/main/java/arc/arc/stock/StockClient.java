package arc.arc.stock;

import arc.arc.board.ItemIcon;
import arc.arc.configs.StockConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.type.MapType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import lombok.RequiredArgsConstructor;
import org.bukkit.Material;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.WebSocketClient;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
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

    public Optional<List<Stock>> fetchCrypto(){
        String ids = StockConfig.currencyDataMap.values().stream()
                .filter(StockConfig.CurrencyData::crypto)
                .map(StockConfig.CurrencyData::id)
                .collect(Collectors.joining("%2C"));
        String url = "https://api.coingecko.com/api/v3/simple/price?ids="+ids +"&vs_currencies=usd&precision=full";
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
            //System.out.println("Map: "+map);
            List<Stock> stocks = new ArrayList<>();
            for(var entry : map.entrySet()){
                String name = entry.getKey();
                double cost = entry.getValue().get("usd");
                StockConfig.CurrencyData currencyData = StockConfig.currencyDataMap.get(name.toLowerCase());
                stocks.add(new Stock(name.toUpperCase(), cost, 0.0, System.currentTimeMillis(),
                        currencyData.display(), currencyData.lore(), currencyData.icon(), false, 0L));
            }

            double EURJPY = fetchAndPrintInstrumentPrice("https://ru.investing.com/currencies/eur-jpy");
            double USDRUB = fetchAndPrintInstrumentPrice("https://ru.investing.com/currencies/usd-rub");

            if(EURJPY != -1){
                //System.out.println("EURUSD: "+EURUSD);
                StockConfig.CurrencyData currencyData = StockConfig.currencyDataMap.get("EUR/JPY");
                stocks.add(new Stock("EUR/JPY", EURJPY, 0, System.currentTimeMillis(),
                        currencyData.display(), currencyData.lore(), currencyData.icon(), false, 0L));
            }
            if(USDRUB != -1){
                //System.out.println("USDJPY: "+USDJPY);
                StockConfig.CurrencyData currencyData = StockConfig.currencyDataMap.get("USD/RUB");
                stocks.add(new Stock("USD/RUB", USDRUB, 0, System.currentTimeMillis(),
                        currencyData.display(), currencyData.lore(), currencyData.icon(), false, 0L));
            }

            return Optional.of(stocks);

        } catch (Exception e) {
            System.out.println("Could not load price for crypto!");
            return Optional.empty();
        }
    }

    private static double fetchAndPrintInstrumentPrice(String url) {
        try {
            // Fetch the HTML content from the URL
            Document document = Jsoup.connect(url).get();

            // Find the <div> tag with the data-test attribute
            Element divElement = document.select("div[data-test=instrument-price-last]").first();

            // Print the content of the found <div> tag
            if (divElement != null) {
                return Double.parseDouble(divElement.text().replace(",", "."));
            } else {
                System.out.println("Could not extract "+url);
                System.out.println(document.text());
                return  -1;
            }

        } catch (Exception e) {
            e.printStackTrace();
            return -1;
        }
    }

/*    public Optional<Stock> fetch(String symbol){
        try {
            double price = price(symbol);
            double dividend = dividend(symbol);
            long timestamp = System.currentTimeMillis();
            return Optional.of(new Stock(symbol, price, dividend, timestamp));
        } catch (Exception e){
            System.out.println("Could not fetch "+symbol);
            //e.printStackTrace();
            return Optional.empty();
        }
    }*/

    public void startWebSocket(Collection<String> symbols){
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
                                client.getSession().getRemote().sendString("{\"type\":\"subscribe\",\"symbol\":\""+s+"\"}");
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
                    e.printStackTrace();
                }
            });
        } catch (Exception e){
            isClosed = true;
            e.printStackTrace();
        }
    }

    public static void stopClient(){
        if(webSocketClient != null && !isClosed) {
            try {
                webSocketClient.stop();
                isClosed = true;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public Double price(String symbol){
        if(webSocketClient == null || !webSocketClient.isRunning() || isClosed){
            startWebSocket(StockMarket.stocks().stream().filter(Stock::isStock).map(Stock::getSymbol).toList());
        }
        try {
            OptionalDouble optional = prices.remove(symbol).stream().mapToDouble(Double::doubleValue).average();
            if (optional.isEmpty()) return null;
            return optional.getAsDouble();
        } catch (Exception e){
            return null;
        }
/*        if(POLY_API_KEY == null) return 0.0;
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
            System.out.println("Could not load price for  "+symbol);
            throw new RuntimeException(e);
        }*/
    }

    public double dividend(String symbol){
        if(POLY_API_KEY == null) return 0;
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
            return (double) ((Map<String, Object>)((List<Object>)map.get("results")).get(0)).get("cash_amount");
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
            System.out.println("WebSocket Connected: " + sess);
            latch.countDown(); // Notify that the connection has been established
            //isClosed = false;
        }

        @Override
        public void onWebSocketText(String message) {
            super.onWebSocketText(message);
            //System.out.println("Received message from server: " + message);
            try {
                Map map = new ObjectMapper().readValue(message, Map.class);
                double price = ((Number) ((Map)((List)map.get("data")).get(0)).get("p")).doubleValue();
                String symbol = ((String) ((Map)((List)map.get("data")).get(0)).get("s"));
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
            cause.printStackTrace();
        }
    }

}
