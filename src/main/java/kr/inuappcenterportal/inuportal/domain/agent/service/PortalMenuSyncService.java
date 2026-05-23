package kr.inuappcenterportal.inuportal.domain.agent.service;

import kr.inuappcenterportal.inuportal.domain.agent.model.PortalMenuMap;
import kr.inuappcenterportal.inuportal.domain.agent.repository.PortalMenuMapRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class PortalMenuSyncService {

    private final PortalMenuMapRepository repository;

    private static final Map<String, String> KEYWORD_PRESETS = new HashMap<>();

    static {
        KEYWORD_PRESETS.put("개인학적조회", "학적,성명,학번,전공,학과,지도교수,학생정보,신상");
        KEYWORD_PRESETS.put("학적부변경신청", "학적,변경,신청,개명,수정");
        KEYWORD_PRESETS.put("장학", "장학,장학금,수혜,수납,장학생");
        KEYWORD_PRESETS.put("등록금", "등록금,고지서,수납,학비,은행,가상계좌");
        KEYWORD_PRESETS.put("성적", "성적,학점,GPA,평점,성적표,취득학점");
        KEYWORD_PRESETS.put("시간표", "시간표,강의,수업,교시,강의실,과목");
    }

    /**
     * 넥사크로에서 콘솔 카피로 추출한 DS_MENU XML 문자열을 파싱하여 
     * MySQL 데이터베이스에 통째로 적재/갱신(Upsert)합니다.
     */
    @Transactional
    public int syncMenuXml(String xmlData) {
        if (xmlData == null || xmlData.isBlank()) {
            throw new IllegalArgumentException("XML 데이터가 비어 있습니다.");
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            Document document = factory.newDocumentBuilder().parse(new InputSource(new StringReader(xmlData)));
            NodeList rows = document.getElementsByTagName("Row");

            int insertedCount = 0;
            int updatedCount = 0;

            for (int i = 0; i < rows.getLength(); i++) {
                Element row = (Element) rows.item(i);
                
                String menuId = findColumnValue(row, "menuId");
                String pgmId = findColumnValue(row, "pgmId");
                String menuNm = findColumnValue(row, "menuNm");
                String menuGbn = findColumnValue(row, "menuGbn");
                String upMenuId = findColumnValue(row, "upMenuId");
                String menuLvlStr = findColumnValue(row, "menuLvl");
                String pgmPath = findColumnValue(row, "pgmPath");
                String pgmFileNm = findColumnValue(row, "pgmFileNm");

                // 필수 식별자가 누락된 경우만 스킵 (pgmId는 폴더의 경우 null일 수 있음)
                if (menuId == null || menuNm == null) {
                    continue;
                }

                Integer menuLvl = null;
                if (menuLvlStr != null) {
                    try {
                        menuLvl = Integer.parseInt(menuLvlStr);
                    } catch (NumberFormatException e) {
                        log.debug("menuLvl 파싱 실패: {}", menuLvlStr);
                    }
                }

                // 한글 메뉴명을 기준으로 검색 최적화용 키워드 프리셋 매핑
                String intentKeywords = resolveDefaultKeywords(menuNm);

                Optional<PortalMenuMap> existingMapOpt = repository.findByMenuId(menuId);
                if (existingMapOpt.isPresent()) {
                    PortalMenuMap existingMap = existingMapOpt.get();
                    // 기존 레코드가 있으면 키워드 및 계층 정보 갱신 (추후 필요시 확장)
                    existingMap.updateKeywords(intentKeywords);
                    updatedCount++;
                } else {
                    // 신규 레코드 생성
                    PortalMenuMap newMap = PortalMenuMap.builder()
                            .menuId(menuId)
                            .pgmId(pgmId)
                            .menuNm(menuNm)
                            .menuGbn(menuGbn)
                            .upMenuId(upMenuId)
                            .menuLvl(menuLvl)
                            .pgmPath(pgmPath)
                            .pgmFileNm(pgmFileNm)
                            .intentKeywords(intentKeywords)
                            .build();
                    repository.save(newMap);
                    insertedCount++;
                }
            }

            log.info("인천대 포털 메뉴 동기화 완료 - 신규 등록: {}건, 기존 갱신: {}건", insertedCount, updatedCount);
            return insertedCount + updatedCount;

        } catch (Exception e) {
            log.error("XML 메뉴 동기화 처리 실패: {}", e.getMessage(), e);
            throw new RuntimeException("메뉴 XML 동기화 중 오류가 발생했습니다: " + e.getMessage());
        }
    }

    private String findColumnValue(Element row, String columnId) {
        NodeList columns = row.getElementsByTagName("Col");
        for (int i = 0; i < columns.getLength(); i++) {
            Element column = (Element) columns.item(i);
            if (columnId.equals(column.getAttribute("id"))) {
                String text = column.getTextContent();
                return (text == null || text.isBlank()) ? null : text.trim();
            }
        }
        return null;
    }

    private String resolveDefaultKeywords(String menuNm) {
        StringBuilder keywords = new StringBuilder();
        keywords.append(menuNm);
        
        for (Map.Entry<String, String> entry : KEYWORD_PRESETS.entrySet()) {
            if (menuNm.contains(entry.getKey())) {
                keywords.append(",").append(entry.getValue());
            }
        }
        return keywords.toString();
    }
}
