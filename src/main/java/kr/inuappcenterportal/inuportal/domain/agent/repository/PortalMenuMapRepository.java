package kr.inuappcenterportal.inuportal.domain.agent.repository;

import kr.inuappcenterportal.inuportal.domain.agent.model.PortalMenuMap;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PortalMenuMapRepository extends JpaRepository<PortalMenuMap, Long> {

    Optional<PortalMenuMap> findByMenuId(String menuId);

    Optional<PortalMenuMap> findByMenuNm(String menuNm);

    /**
     * 사용자의 입력 키워드 기반으로 데이터베이스에서 매칭되는 '프로그램(P)' 메뉴 리스트를 검색합니다.
     * (폴더 메뉴인 'M'은 검색 대상에서 제외하여 할루시네이션을 방지합니다)
     */
    @Query("SELECT p FROM PortalMenuMap p WHERE (p.menuNm LIKE %:keyword% OR p.intentKeywords LIKE %:keyword%) AND p.menuGbn = 'P'")
    List<PortalMenuMap> searchProgramsByKeyword(@Param("keyword") String keyword);
}
