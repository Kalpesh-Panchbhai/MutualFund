package com.kalpesh.mutualfund.service;

import com.kalpesh.mutualfund.model.Scheme;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class AmfiService {

    private static final String NAV_URL = "https://portal.amfiindia.com/spages/NAVAll.txt";

    private static final Pattern CATEGORY_PATTERN = Pattern.compile(
            "^(Open Ended Schemes|Close Ended Schemes|Interval Fund Schemes)\\s*\\(\\s*(.+?)\\s*-\\s*(.+?)\\s*\\)\\s*$"
    );

    private static final Pattern CATEGORY_NO_SUB_PATTERN = Pattern.compile(
            "^(Open Ended Schemes|Close Ended Schemes|Interval Fund Schemes)\\s*\\(\\s*(.+?)\\s*\\)\\s*$"
    );

    private static final String MFAPI_URL = "https://api.mfapi.in/mf/";
    private static final DateTimeFormatter MFAPI_DATE_FMT = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newFixedThreadPool(20);

    public List<Scheme> fetchAllSchemes() throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(NAV_URL))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return parse(response.body());
    }

    public void streamCagr(List<Scheme> schemes, java.util.function.BiConsumer<Long, Double> onResult, Runnable onComplete) {
        List<CompletableFuture<Void>> futures = schemes.stream()
                .map(scheme -> CompletableFuture.runAsync(() -> {
                    Double cagr = calculateCagr1Y(scheme.getCode(), scheme.getNav());
                    scheme.setCagr1Y(cagr);
                    onResult.accept(scheme.getCode(), cagr);
                }, executor))
                .toList();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(onComplete);
    }

    public Double calculateCagr1Y(long schemeCode, double currentNav) {
        if (currentNav <= 0) return null;
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(MFAPI_URL + schemeCode))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode data = root.get("data");
            if (data == null || !data.isArray() || data.isEmpty()) return null;

            LocalDate targetDate = LocalDate.now().minusYears(1);
            Double nav1Y = findNavNearDate(data, targetDate);
            if (nav1Y == null || nav1Y <= 0) return null;

            return (Math.pow(currentNav / nav1Y, 1.0) - 1) * 100;
        } catch (Exception e) {
            return null;
        }
    }

    private Double findNavNearDate(JsonNode data, LocalDate target) {
        Double closestNav = null;
        long closestDiff = Long.MAX_VALUE;

        for (JsonNode entry : data) {
            try {
                String dateStr = entry.get("date").asText();
                LocalDate date = LocalDate.parse(dateStr, MFAPI_DATE_FMT);
                long diff = Math.abs(date.toEpochDay() - target.toEpochDay());
                if (diff < closestDiff && diff <= 7) {
                    closestDiff = diff;
                    closestNav = Double.parseDouble(entry.get("nav").asText());
                }
            } catch (Exception ignored) {
            }
        }
        return closestNav;
    }

    private List<Scheme> parse(String rawData) {
        List<Scheme> schemes = new ArrayList<>();
        String[] lines = rawData.split("\\r?\\n");

        String currentSchemeType = "";
        String currentCategory = "";
        String currentSubCategory = "";
        String currentFundHouse = "";

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }

            Matcher categoryMatcher = CATEGORY_PATTERN.matcher(line);
            Matcher categoryNoSubMatcher = CATEGORY_NO_SUB_PATTERN.matcher(line);

            if (categoryMatcher.matches()) {
                currentSchemeType = categoryMatcher.group(1).trim();
                currentCategory = categoryMatcher.group(2).trim();
                currentSubCategory = categoryMatcher.group(3).trim();
                currentFundHouse = "";
                continue;
            }

            if (categoryNoSubMatcher.matches()) {
                currentSchemeType = categoryNoSubMatcher.group(1).trim();
                currentCategory = categoryNoSubMatcher.group(2).trim();
                currentSubCategory = "";
                currentFundHouse = "";
                continue;
            }

            String[] parts = line.split(";");

            if (parts.length >= 5 && !line.startsWith("Scheme Code")) {
                try {
                    long code = Long.parseLong(parts[0].trim());
                    String name = parts[3].trim();
                    String navStr = parts[4].trim();

                    double nav;
                    try {
                        nav = Double.parseDouble(navStr);
                    } catch (NumberFormatException e) {
                        nav = 0;
                    }

                    Scheme scheme = new Scheme();
                    scheme.setCode(code);
                    scheme.setName(name);
                    scheme.setFundHouse(currentFundHouse);
                    scheme.setSchemeType(currentSchemeType);
                    scheme.setCategory(currentCategory);
                    scheme.setSubCategory(currentSubCategory);
                    scheme.setNav(nav);
                    scheme.setDate(parts.length >= 6 ? parts[5].trim() : "");

                    schemes.add(scheme);
                } catch (NumberFormatException ignored) {
                }
            } else if (parts.length == 1 && !line.contains(";")) {
                currentFundHouse = line.trim();
            }
        }
        return schemes;
    }
}
