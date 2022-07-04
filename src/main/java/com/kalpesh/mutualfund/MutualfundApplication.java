package com.kalpesh.mutualfund;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.client.RestTemplate;

import java.io.File;
import java.io.FileOutputStream;
import java.util.*;
import java.util.stream.Collectors;

@SpringBootApplication
public class MutualfundApplication implements CommandLineRunner {

    public static void main(String[] args) {
        SpringApplication.run(MutualfundApplication.class, args);
    }

    private static void createList(Scheme scheme, Row row, XSSFWorkbook workbook) {
        Cell cell = row.createCell(0);
        cell.setCellValue(scheme.getName());
        cell = row.createCell(1);
        cell.setCellFormula("RATE(5,,-1*" + scheme.getPastValue() + "," + scheme.getCurrentValue() + ")");
        CellStyle style = workbook.createCellStyle();
        style.setDataFormat(workbook.createDataFormat().getFormat("0.000%"));
        cell.setCellStyle(style);
    }

    @Override
    public void run(String... args) {
        Map<Long, Scheme> schemeList = new HashMap<>();
        getSchemes("30-Jun-2022", schemeList, false);
        getSchemes("03-Jul-2017", schemeList, true);
        List<Scheme> schemes = new ArrayList<>(schemeList.entrySet().stream().filter(longSchemeEntry -> longSchemeEntry.getValue().getCurrentValue() != 0.0 && longSchemeEntry.getValue().getPastValue() != 0.0).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)).values().stream().toList());

        schemes.forEach(value -> value.setTotalRateOfInterest((value.getCurrentValue() - value.getPastValue()) / value.getPastValue() * 100));

        schemes.sort(Comparator.comparing(Scheme::getTotalRateOfInterest).reversed());
        schemes = schemes.stream().filter(scheme -> scheme.getTotalRateOfInterest() > 0 && scheme.getName().toLowerCase().contains("direct") && !scheme.getName().toLowerCase().contains("idcw")).collect(Collectors.toList());
        try {
            XSSFWorkbook workbook = new XSSFWorkbook();

            XSSFSheet sheet = workbook.createSheet("Mutual Fund History");
            int rownum = 0;
            Row row = sheet.createRow(rownum++);
            Cell cell = row.createCell(0);
            cell.setCellValue("Schema Name");
            cell = row.createCell(1);
            cell.setCellValue("Compounded Rate of Interest");
            for (Scheme scheme : schemes) {
                row = sheet.createRow(rownum++);
                createList(scheme, row, workbook);
            }

            FileOutputStream out = new FileOutputStream(new File("Mutual_fund_history.xlsx")); // file name with path
            workbook.write(out);
            out.close();

        } catch (Exception e) {
            e.printStackTrace();
        }

    }

    private void getSchemes(String date, Map<Long, Scheme> schemeList, boolean pastData) {
        final String uri = "https://portal.amfiindia.com/DownloadNAVHistoryReport_Po.aspx?frmdt=" + date + "&todt=" + date;

        RestTemplate restTemplate = new RestTemplate();
        String result = restTemplate.getForObject(uri, String.class);

        assert result != null;
        result = result.replace("/\r?\n/g", "\n");
        String[] bodyArr = result.split("\n");
        List<String[]> list = Arrays.stream(bodyArr).map(s -> s.split(";")).toList();
        list = list.stream().skip(1).toList();
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).length == 8) {
                Scheme scheme = null;
                for (int j = 0; j < 8; j++) {
                    List<String[]> finalList = list;
                    int finalI = i;
                    if (schemeList.containsKey(Long.parseLong(finalList.get(finalI)[0]))) {
                        if (j == 4) {
                            try {
                                int finalJ = j;
                                schemeList.forEach((key, value) -> {
                                    if (key == Long.parseLong(finalList.get(finalI)[0])) {
                                        if (pastData) {
                                            value.setPastValue(Double.parseDouble(finalList.get(finalI)[finalJ]));
                                        } else {
                                            value.setCurrentValue(Double.parseDouble(finalList.get(finalI)[finalJ]));
                                        }
                                    }
                                });
                            } catch (Exception ignored) {
                            }
                        }
                    } else {
                        if (j == 0) {
                            scheme = new Scheme();
                            scheme.setCode(Long.parseLong(list.get(i)[j]));
                        } else if (j == 1) {
                            try {
                                assert scheme != null;
                                scheme.setName(list.get(i)[j]);
                            } catch (Exception e) {
                                System.out.println(i);
                            }
                        } else if (j == 4) {
                            try {
                                if (pastData) {
                                    assert scheme != null;
                                    scheme.setPastValue(Double.parseDouble(list.get(i)[j]));
                                } else {
                                    assert scheme != null;
                                    scheme.setCurrentValue(Double.parseDouble(list.get(i)[j]));
                                }
                                schemeList.put(Long.valueOf(list.get(i)[0]), scheme);
                            } catch (Exception ignored) {
                            }
                        }
                    }
                }
            }
        }
    }
}

