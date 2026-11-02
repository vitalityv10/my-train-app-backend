package org.ipz.backend.service;

import org.ipz.backend.model.Train;
import org.ipz.backend.model.dto.TrainFilter;

import java.util.List;

public interface TrainService {

    List<Train> findTrains(TrainFilter filter);

    Train findById(Integer id);

    List<String> findAllCities();

    List<Train> seedInitialTrains();
}
