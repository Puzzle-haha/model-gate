package com.modelgate.eval;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface EvalRunRepository extends JpaRepository<EvalRun, Long> {

    List<EvalRun> findTop20ByOrderByIdDesc();
}
