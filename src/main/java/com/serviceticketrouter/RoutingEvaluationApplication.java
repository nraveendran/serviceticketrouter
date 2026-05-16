package com.serviceticketrouter;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import com.serviceticketrouter.routes.RoutePredictionCandidate;
import com.serviceticketrouter.routes.RoutePredictionService;

public class RoutingEvaluationApplication {
    private static final Logger LOGGER = Logger.getLogger(RoutingEvaluationApplication.class.getName());

    public static void main(String[] args) throws Exception {
        SpringApplication application = new SpringApplication(ServiceTicketRouterApplication.class);
        application.setWebApplicationType(WebApplicationType.NONE);

        try (ConfigurableApplicationContext context = application.run(args)) {
            RoutePredictionService routePredictionService = context.getBean(RoutePredictionService.class);
            DatabaseConfig databaseConfig = DatabaseConfig.from(context.getEnvironment());


            List<TestRow> testRows = fetchTestRows(databaseConfig);
            LOGGER.info(() -> "Loaded " + testRows.size() + " test rows for routing evaluation.");

            int evaluatedCount = 0;
            int failedCount = 0;
            int top1CorrectCount = 0;
            int top3CorrectCount = 0;

            for (TestRow testRow : testRows) {
                try {
                    List<RoutePredictionCandidate> candidates = routePredictionService.predictCandidates(
                            testRow.generatedDescription(),
                            true
                    );

                    RoutePredictionCandidate topCandidate = candidates.get(0);
                    boolean top1Correct = matchesActual(testRow, topCandidate);
                    boolean top3Correct = candidates.stream().anyMatch(candidate -> matchesActual(testRow, candidate));
                    boolean departmentCorrect = Objects.equals(testRow.department(), topCandidate.department());
                    boolean serviceTypeCorrect = Objects.equals(testRow.serviceRequestType(), topCandidate.serviceRequestType());
                    boolean priorityCorrect = Objects.equals(testRow.priority(), topCandidate.priority());
                    Double scoreGap = candidates.size() > 1
                            ? topCandidate.score() - candidates.get(1).score()
                            : null;

                    insertEvaluationResult(
                            databaseConfig,
                            testRow,
                            topCandidate,
                            top1Correct,
                            top3Correct,
                            departmentCorrect,
                            serviceTypeCorrect,
                            priorityCorrect,
                            scoreGap
                    );

                    evaluatedCount++;
                    if (top1Correct) {
                        top1CorrectCount++;
                    }
                    if (top3Correct) {
                        top3CorrectCount++;
                    }

                    int currentEvaluatedCount = evaluatedCount;
                    LOGGER.info(() -> "Evaluated serviceRequestNumber=" + testRow.serviceRequestNumber()
                            + ", top1Correct=" + top1Correct
                            + ", top3Correct=" + top3Correct
                            + ", departmentCorrect=" + departmentCorrect
                            + ", serviceTypeCorrect=" + serviceTypeCorrect
                            + ", priorityCorrect=" + priorityCorrect
                            + ", evaluatedCount=" + currentEvaluatedCount);
                } catch (Exception e) {
                    failedCount++;
                    LOGGER.log(
                            Level.WARNING,
                            "Failed to evaluate serviceRequestNumber=" + testRow.serviceRequestNumber(),
                            e
                    );
                }
            }

            double top1Accuracy = evaluatedCount == 0 ? 0.0 : (double) top1CorrectCount / evaluatedCount;
            double top3Accuracy = evaluatedCount == 0 ? 0.0 : (double) top3CorrectCount / evaluatedCount;
            LOGGER.info("Routing evaluation finished. evaluatedCount=" + evaluatedCount
                    + ", failedCount=" + failedCount
                    + ", top1Accuracy=" + top1Accuracy
                    + ", top3Accuracy=" + top3Accuracy);
        }
    }


    private static List<TestRow> fetchTestRows(DatabaseConfig config) throws SQLException {
        String sql = """
                SELECT
                    service_request_number,
                    generated_description,
                    service_request_type,
                    department,
                    priority
                FROM synthetic_service_request_descriptions
                WHERE dataset_split = 'test'
                  AND generated_description IS NOT NULL
                ORDER BY service_request_number
                """;

        List<TestRow> testRows = new ArrayList<>();
        try (Connection connection = openConnection(config);
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                testRows.add(new TestRow(
                        resultSet.getString("service_request_number"),
                        resultSet.getString("generated_description"),
                        resultSet.getString("service_request_type"),
                        resultSet.getString("department"),
                        resultSet.getString("priority")
                ));
            }
        }
        return testRows;
    }

    private static void insertEvaluationResult(
            DatabaseConfig config,
            TestRow testRow,
            RoutePredictionCandidate prediction,
            boolean top1Correct,
            boolean top3Correct,
            boolean departmentCorrect,
            boolean serviceTypeCorrect,
            boolean priorityCorrect,
            Double scoreGap
    ) throws SQLException {
        String sql = """
                INSERT INTO routing_evaluation_results (
                    service_request_number,
                    input_description,
                    actual_service_request_type,
                    actual_department,
                    actual_priority,
                    predicted_service_request_type,
                    predicted_department,
                    predicted_priority,
                    top1_correct,
                    top3_correct,
                    department_correct,
                    service_type_correct,
                    priority_correct,
                    confidence,
                    score_gap
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (Connection connection = openConnection(config);
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, testRow.serviceRequestNumber());
            statement.setString(2, testRow.generatedDescription());
            statement.setString(3, testRow.serviceRequestType());
            statement.setString(4, testRow.department());
            statement.setString(5, testRow.priority());
            statement.setString(6, prediction.serviceRequestType());
            statement.setString(7, prediction.department());
            statement.setString(8, prediction.priority());
            statement.setBoolean(9, top1Correct);
            statement.setBoolean(10, top3Correct);
            statement.setBoolean(11, departmentCorrect);
            statement.setBoolean(12, serviceTypeCorrect);
            statement.setBoolean(13, priorityCorrect);
            statement.setDouble(14, prediction.confidence());
            if (scoreGap == null) {
                statement.setNull(15, java.sql.Types.DOUBLE);
            } else {
                statement.setDouble(15, scoreGap);
            }
            statement.executeUpdate();
        }
    }

    private static boolean matchesActual(TestRow testRow, RoutePredictionCandidate candidate) {
        return Objects.equals(testRow.serviceRequestType(), candidate.serviceRequestType())
                && Objects.equals(testRow.department(), candidate.department())
                && Objects.equals(testRow.priority(), candidate.priority());
    }

    private static Connection openConnection(DatabaseConfig config) throws SQLException {
        return DriverManager.getConnection(
                config.databaseUrl(),
                config.databaseUsername(),
                config.databasePassword()
        );
    }

    private record TestRow(
            String serviceRequestNumber,
            String generatedDescription,
            String serviceRequestType,
            String department,
            String priority
    ) {
    }

    private record DatabaseConfig(
            String databaseUrl,
            String databaseUsername,
            String databasePassword
    ) {
        private static DatabaseConfig from(Environment environment) {
            return new DatabaseConfig(
                    environment.getProperty("database.url", "jdbc:postgresql://127.0.0.1:5432/servicerouterdb"),
                    environment.getProperty("database.username", "postgres"),
                    environment.getProperty("database.password", "")
            );
        }
    }
}
