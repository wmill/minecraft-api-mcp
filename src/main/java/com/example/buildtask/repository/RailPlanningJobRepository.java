package com.example.buildtask.repository;

import com.example.buildtask.model.RailPlanningJob;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

public interface RailPlanningJobRepository {
    RailPlanningJob create(RailPlanningJob job) throws SQLException;

    RailPlanningJob update(RailPlanningJob job) throws SQLException;

    Optional<RailPlanningJob> findById(UUID id) throws SQLException;
}
