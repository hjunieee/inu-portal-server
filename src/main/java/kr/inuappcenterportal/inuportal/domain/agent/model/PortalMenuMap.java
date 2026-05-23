package kr.inuappcenterportal.inuportal.domain.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Table(name = "portal_menu_map")
public class PortalMenuMap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "menu_id", nullable = false, unique = true)
    private String menuId;      // M002043

    @Column(name = "pgm_id")
    private String pgmId;       // P001878 (nullable for folders)

    @Column(name = "menu_nm", nullable = false)
    private String menuNm;      // 개인학적조회(학생)

    @Column(name = "menu_gbn")
    private String menuGbn;     // M: Folder, P: Program

    @Column(name = "up_menu_id")
    private String upMenuId;    // Parent Menu ID

    @Column(name = "menu_lvl")
    private Integer menuLvl;    // Menu Depth (0 for Top)

    @Column(name = "pgm_path")
    private String pgmPath;     // uni/sreg/tsim

    @Column(name = "pgm_file_nm")
    private String pgmFileNm;   // tsim0350

    @Column(name = "intent_keywords", columnDefinition = "TEXT")
    private String intentKeywords; // 검색 매칭용 키워드 쉼표 구분자 리스트

    @Builder
    public PortalMenuMap(String menuId, String pgmId, String menuNm, String menuGbn, String upMenuId, Integer menuLvl, String pgmPath, String pgmFileNm, String intentKeywords) {
        this.menuId = menuId;
        this.pgmId = pgmId;
        this.menuNm = menuNm;
        this.menuGbn = menuGbn;
        this.upMenuId = upMenuId;
        this.menuLvl = menuLvl;
        this.pgmPath = pgmPath;
        this.pgmFileNm = pgmFileNm;
        this.intentKeywords = intentKeywords;
    }

    public void updateKeywords(String intentKeywords) {
        this.intentKeywords = intentKeywords;
    }
}
