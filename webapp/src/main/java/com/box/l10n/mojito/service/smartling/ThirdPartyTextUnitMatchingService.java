package com.box.l10n.mojito.service.smartling;

import com.box.l10n.mojito.entity.Asset;
import com.box.l10n.mojito.entity.ThirdPartyTextUnit;
import com.box.l10n.mojito.smartling.SmartlingClient;
import com.box.l10n.mojito.smartling.request.File;
import com.box.l10n.mojito.smartling.request.StringInfo;
import com.box.l10n.mojito.smartling.response.FilesResponse;
import com.box.l10n.mojito.smartling.response.SourceStringsResponse;
import com.box.l10n.mojito.utils.Optionals;
import com.box.l10n.mojito.utils.PageFetcherSplitIterator;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.box.l10n.mojito.utils.Predicates.logIfFalse;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Service to manage matching between mojito text units and third party text units
 **/
@Service
public class ThirdPartyTextUnitMatchingService {
    /**
     * logger
     */
    private static Logger logger = getLogger(ThirdPartyTextUnitMatchingService.class);

    @Autowired
    ThirdPartyTextUnitRepository thirdPartyTextUnitRepository;

    @Autowired
    SmartlingClient smartlingClient;

    @Autowired
    ThirdPartyTextUnitSearchService thirdPartyTextUnitSearchService;

    String delimiter = "#@#";

    private String fileRegex = ".*\\d+_(singular|plural)_source\\.xml";

    private String pluralFileIndicator = "plural";

    void getSmartlingThirdPartyTextUnits(String projectId) {
        FilesResponse files = smartlingClient.getFiles(projectId);
        for (File file : files.getResponse().getData().getItems()) {
            String fileUri = file.getFileUri();
            if (fileUri.matches(fileRegex)) {
                processFile(fileUri, projectId);
            }
        }
    }

    public void processFile(String fileUri, String projectId) {
        int maxResults = 500;

        // pull all strings for a file then match
        PageFetcherSplitIterator<StringInfo> sourceStringsPageFetcherSplitIterator = new PageFetcherSplitIterator<>(
            (offset, limit) -> {
                List<StringInfo> stringInfoList = new ArrayList<>();
                SourceStringsResponse sourceStringsResponse = smartlingClient.getSourceStrings(projectId, fileUri, offset);
                if (sourceStringsResponse.isSuccessResponse()) {
                    stringInfoList = sourceStringsResponse.getResponse().getData().getItems();
                }
                return stringInfoList;
            }, maxResults
        );
        Stream<StringInfo> stream = StreamSupport.stream(sourceStringsPageFetcherSplitIterator, false);
        List<StringInfo> stringInfoToCheck = stream.collect(Collectors.toList());


        logger.debug("done pulling strings for file {}, starting matching", fileUri);
        Instant start = Instant.now();
        Set<ThirdPartyTextUnitDTO> thirdPartyTextUnitDTOSet = convertStringInfoToDTO(stringInfoToCheck, fileUri);
        if (!thirdPartyTextUnitDTOSet.isEmpty()) {
            List<ThirdPartyTextUnitForBatchImport> thirdPartyTextUnitForBatchImportList = thirdPartyTextUnitSearchService.convertDTOToBatchImport(
                    thirdPartyTextUnitDTOSet, fileUri.contains(pluralFileIndicator));

            logger.debug("Batch by asset");
            Map<Asset, List<ThirdPartyTextUnitForBatchImport>> groupedByAsset = thirdPartyTextUnitForBatchImportList
                    .stream()
                    .collect(Collectors.groupingBy(ThirdPartyTextUnitForBatchImport::getAsset));

            for (Map.Entry<Asset, List<ThirdPartyTextUnitForBatchImport>> entry : groupedByAsset.entrySet()) {
                mapThirdPartyTextUnitsToImportWithExisting(entry.getKey(), entry.getValue());
                importThirdPartyTextUnitOfAsset(entry.getValue());
            }
        }

        Instant end = Instant.now();
        logger.debug("ThirdPartyTextUnit matching and saving start for file {}: {}", fileUri, start);
        logger.debug("ThirdPartyTextUnit matching and saving end for file {}: {}", fileUri, end);
    }

    @Transactional
    void importThirdPartyTextUnitOfAsset(List<ThirdPartyTextUnitForBatchImport> thirdPartyTextUnitsToImport) {
        logger.debug("Start importing third party text units");

        thirdPartyTextUnitsToImport
                .stream()
                .filter(logIfFalse(t -> t.getThirdPartyTextUnitId() != null, logger, "Third party text unit id can't be null, skip: {}", ThirdPartyTextUnitForBatchImport::getThirdPartyTextUnitId))
                .filter(logIfFalse(t -> t.getTmTextUnit() != null, logger, "Text unit match can't be null, skip: {}", ThirdPartyTextUnitForBatchImport::getThirdPartyTextUnitId))
                .filter(logIfFalse(this::isUpdateNeededForThirdPartyTextUnit, logger, "Update not needed, skip: {}", ThirdPartyTextUnitForBatchImport::getThirdPartyTextUnitId))
                .forEach(thirdPartyTextUnitForBatchImport -> {
                    logger.debug("third party text unit {}: {} corresponding to text unit {} did not find existing match in db",
                            thirdPartyTextUnitForBatchImport.getThirdPartyTextUnitId(),
                            thirdPartyTextUnitForBatchImport.getMappingKey(),
                            thirdPartyTextUnitForBatchImport.getTmTextUnit().getId()
                            );
                    ThirdPartyTextUnit thirdPartyTextUnit = new ThirdPartyTextUnit();
                    ThirdPartyTextUnit existingThirdPartyTextUnitMatch = thirdPartyTextUnitRepository.findByThirdPartyTextUnitId(
                            thirdPartyTextUnitForBatchImport.getThirdPartyTextUnitId());
                    if (existingThirdPartyTextUnitMatch != null) {
                        logger.info("re-used thirdPartyTextUnitId {}", thirdPartyTextUnitForBatchImport.getThirdPartyTextUnitId());
                    } else {
                        // no matching ThirdPartyTextUnit, create new one
                        thirdPartyTextUnit.setThirdPartyTextUnitId(thirdPartyTextUnitForBatchImport.getThirdPartyTextUnitId());
                        thirdPartyTextUnit.setMappingKey(thirdPartyTextUnitForBatchImport.getMappingKey());
                        thirdPartyTextUnit.setTmTextUnit(thirdPartyTextUnitForBatchImport.getTmTextUnit());
                        thirdPartyTextUnitRepository.save(thirdPartyTextUnit);
                    }
                });
    }

    void mapThirdPartyTextUnitsToImportWithExisting(Asset asset, List<ThirdPartyTextUnitForBatchImport> thirdPartyTextUnitsToImport) {
        logger.debug("Map the third party text units to import with current third party text units for the given asset {}", asset.getPath());
        List<ThirdPartyTextUnitDTO> thirdPartyTextUnitIdsAndMappingKeys = thirdPartyTextUnitSearchService.getByThirdPartyTextUnitIdsAndMappingKeys(thirdPartyTextUnitsToImport);
        Function<ThirdPartyTextUnitForBatchImport, Optional<ThirdPartyTextUnitDTO>> match = match(thirdPartyTextUnitIdsAndMappingKeys);
        thirdPartyTextUnitsToImport.forEach(tu -> match.apply(tu).ifPresent(tu::setCurrentThirdPartyTextUnitDTO));
    }

    Set<ThirdPartyTextUnitDTO> convertStringInfoToDTO(List<StringInfo> stringInfoList, String fileUri) {
        Set<ThirdPartyTextUnitDTO> thirdPartyTextUnitDTOSet = new HashSet<>();
        for (StringInfo stringInfo : stringInfoList) {
            if (stringInfo.getStringVariant() != null && stringInfo.getStringVariant().contains(delimiter)) {
                ThirdPartyTextUnitDTO thirdPartyTextUnitDTO = new ThirdPartyTextUnitDTO(
                        null,
                        stringInfo.getHashcode(),
                        stringInfo.getStringVariant(),
                        null);

                String[] mappingKeySplit = stringInfo.getStringVariant().split(delimiter);
                String assetPath = mappingKeySplit[0];
                String name = mappingKeySplit[1];
                thirdPartyTextUnitDTO.setAssetPath(assetPath);
                thirdPartyTextUnitDTO.setTmTextUnitName(name);

                String[] fileUriSplit = fileUri.split("/");
                thirdPartyTextUnitDTO.setRepositoryName(fileUriSplit[0]);

                thirdPartyTextUnitDTOSet.add(thirdPartyTextUnitDTO);
            }
        }
        return thirdPartyTextUnitDTOSet;
    }

    Function<ThirdPartyTextUnitForBatchImport, Optional<ThirdPartyTextUnitDTO>> match(List<ThirdPartyTextUnitDTO> existingThirdPartyTextUnits) {
        Function<ThirdPartyTextUnitForBatchImport, Optional<ThirdPartyTextUnitDTO>> matchByThirdPartyTextUnitIdAndMappingKey = createMatchByThirdPartyTextIdAndMappingKey(
                existingThirdPartyTextUnits);

        return thirdPartyTextUnitForBatchImport -> Optionals.or(thirdPartyTextUnitForBatchImport, matchByThirdPartyTextUnitIdAndMappingKey);
    }

    Function<ThirdPartyTextUnitForBatchImport, Optional<ThirdPartyTextUnitDTO>> createMatchByThirdPartyTextIdAndMappingKey(List<ThirdPartyTextUnitDTO> existingThirdPartyTextUnits) {
        // default map to return in the case that third party text unit is new and therefore not a key in the map
        Map<String, ThirdPartyTextUnitDTO> defaultMap = new HashMap<>();

        logger.debug("Create the maps to match by third party text unit id and mapping key");
        Map<String, Map<String, ThirdPartyTextUnitDTO>> mappingPairTextUnitDTO = existingThirdPartyTextUnits
                .stream()
                .collect(Collectors.groupingBy(t -> t.thirdPartyTextUnitId, Collectors.toMap(t -> t.mappingKey, Function.identity())));

        logger.debug("createMatchByThirdPartyTextIdAndMappingKey");
        return (thirdPartyTextUnitForBatchImport) -> Optional.ofNullable(
                mappingPairTextUnitDTO.getOrDefault(
                        thirdPartyTextUnitForBatchImport.getThirdPartyTextUnitId(), defaultMap).get(thirdPartyTextUnitForBatchImport.getMappingKey()));
    }

    boolean isUpdateNeededForThirdPartyTextUnit(ThirdPartyTextUnitForBatchImport thirdPartyTextUnitForBatchImport) {
        boolean needsUpdating;
        ThirdPartyTextUnitDTO currentThirdPartyTextUnit = thirdPartyTextUnitForBatchImport.getCurrentThirdPartyTextUnitDTO();

        if (currentThirdPartyTextUnit == null) {
            // this is the case of a new ThirdPartyTextUnit, set to true to prevent filtering
            needsUpdating = true;
        } else {
            needsUpdating = !(currentThirdPartyTextUnit.getMappingKey().equals(thirdPartyTextUnitForBatchImport.getMappingKey())
                    && currentThirdPartyTextUnit.getThirdPartyTextUnitId().equals(thirdPartyTextUnitForBatchImport.getThirdPartyTextUnitId())
                    && currentThirdPartyTextUnit.getTmTextUnitId().equals(thirdPartyTextUnitForBatchImport.getTmTextUnit().getId()));
        }
        return needsUpdating;
    }
}