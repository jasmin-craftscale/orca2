package com.lynxis.orca.runtime.readmodel;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.lynxis.orca.platform.scope.ScopeSeam;
import com.lynxis.orca.runtime.readmodel.api.GridController;
import com.lynxis.orca.runtime.readmodel.domain.GridExportService;
import com.lynxis.orca.runtime.readmodel.domain.LaneMonitorService;
import com.lynxis.orca.runtime.readmodel.persistence.GridExportRepository;
import com.lynxis.orca.runtime.readmodel.persistence.LaneMonitorRepository;
import com.lynxis.orca.runtime.workitem.api.WorkItemGridPort;

/** Wires the runtime readmodel module. */
@Configuration(proxyBeanMethods = false)
public class ReadModelConfiguration {

	@Bean
	public LaneMonitorRepository laneMonitorRepository(ScopeSeam seam) {
		return new LaneMonitorRepository(seam);
	}

	@Bean
	public LaneMonitorService laneMonitorService(LaneMonitorRepository repository) {
		return new LaneMonitorService(repository);
	}

	@Bean
	public GridExportRepository gridExportRepository(ScopeSeam seam) {
		return new GridExportRepository(seam);
	}

	@Bean
	public GridExportService gridExportService(GridExportRepository repository,
			LaneMonitorService laneMonitors, WorkItemGridPort workItems,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new GridExportService(repository, laneMonitors, workItems, siteExternalId);
	}

	@Bean
	public GridController gridController(LaneMonitorService laneMonitors, WorkItemGridPort workItems,
			GridExportService exports,
			@Value("${orca.installation.site-external-id}") String siteExternalId) {
		return new GridController(laneMonitors, workItems, exports, siteExternalId);
	}
}
