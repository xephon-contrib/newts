package org.opennms.newts.persistence.cassandra;


import static com.datastax.driver.core.querybuilder.QueryBuilder.batch;
import static com.datastax.driver.core.querybuilder.QueryBuilder.eq;
import static com.datastax.driver.core.querybuilder.QueryBuilder.gte;
import static com.datastax.driver.core.querybuilder.QueryBuilder.insertInto;
import static com.datastax.driver.core.querybuilder.QueryBuilder.lt;

import java.util.Collection;
import java.util.Date;
import java.util.concurrent.TimeUnit;

import javax.inject.Named;

import org.opennms.newts.api.Measurement;
import org.opennms.newts.api.MeasurementRepository;
import org.opennms.newts.api.MetricType;
import org.opennms.newts.api.Results;
import org.opennms.newts.api.Timestamp;
import org.opennms.newts.api.ValueType;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.datastax.driver.core.querybuilder.Batch;
import com.datastax.driver.core.querybuilder.QueryBuilder;
import com.datastax.driver.core.querybuilder.Select;
import com.google.common.base.Optional;
import com.google.inject.Inject;


public class CassandraMeasurementRepository implements MeasurementRepository {

    private static final String T_MEASUREMENTS = "measurements";

    private static final String F_RESOURCE = "resource";
    private static final String F_COLLECTED = "collected_at";
    private static final String F_METRIC_NAME = "metric_name";
    private static final String F_METRIC_TYPE = "metric_type";
    private static final String F_VALUE = "value";
    private static final String F_ATTRIBUTES = "attributes";

    private Session m_session;

    @Inject
    public CassandraMeasurementRepository(@Named("cassandraKeyspace") String keyspace, @Named("cassandraHost") String host, @Named("cassandraPort") int port) {

        Cluster cluster = Cluster.builder().withPort(port).addContactPoint(host).build();
        m_session = cluster.connect(keyspace);

    }

    @Override
    public Results select(String resource, Optional<Timestamp> start, Optional<Timestamp> end) {

        Select select = QueryBuilder.select().from(T_MEASUREMENTS);
        select.where(eq(F_RESOURCE, resource));
 
        if (start.isPresent()) select.where(gte(F_COLLECTED, start.get().asDate()));
        if (end.isPresent()) select.where(lt(F_COLLECTED, end.get().asDate()));

        Results results = new Results();

        for (Row row : m_session.execute(select)) {

            Date timestamp = row.getDate(F_COLLECTED);
            String metricName = row.getString(F_METRIC_NAME);
            MetricType type = MetricType.valueOf(row.getString(F_METRIC_TYPE));
            ValueType<?> value = ValueType.compose(row.getBytes(F_VALUE), type);

            Measurement measurement = new Measurement(
                    new Timestamp(timestamp.getTime(), TimeUnit.MILLISECONDS),
                    resource,
                    metricName,
                    type,
                    value);
            results.addMeasurement(measurement);
        }

        return results;
    }

    @Override
    public void insert(Collection<Measurement> measurements) {

        Batch batch = batch();

        for (Measurement m : measurements) {
            batch.add(
                    insertInto(T_MEASUREMENTS)
                        .value(F_RESOURCE, m.getResource())
                        .value(F_COLLECTED, m.getTimestamp().asMillis())
                        .value(F_METRIC_NAME, m.getName())
                        .value(F_METRIC_TYPE, m.getType().toString())
                        .value(F_VALUE, ValueType.decompose(m.getValue()))
                        .value(F_ATTRIBUTES, m.getAttributes())
           );
        }

        m_session.execute(batch);

    }

}