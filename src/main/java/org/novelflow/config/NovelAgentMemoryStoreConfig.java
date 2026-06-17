package org.novelflow.config;

import com.alibaba.cloud.ai.graph.store.Store;
import com.alibaba.cloud.ai.graph.store.stores.DatabaseStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
public class NovelAgentMemoryStoreConfig {

    @Bean
    public Store novelFlowAgentStore(DataSource dataSource) {
        return new DatabaseStore(dataSource, "t_novelflow_agent_store");
    }
}
