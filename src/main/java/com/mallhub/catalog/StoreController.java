package com.mallhub.catalog;

import com.mallhub.mall.MallRepository;
import java.io.IOException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
public class StoreController {

    private final StoreService stores;
    private final MallRepository malls;

    public StoreController(StoreService stores, MallRepository malls) {
        this.stores = stores;
        this.malls = malls;
    }

    // Public: search stores across every mall
    @GetMapping("/stores")
    public String search(@RequestParam(defaultValue = "") String q,
                         @RequestParam(defaultValue = "0") long category,
                         @RequestParam(defaultValue = "0") long mall,
                         Model model) {
        model.addAttribute("stores", stores.search(q, category, mall));
        model.addAttribute("categories", stores.categories());
        model.addAttribute("malls", malls.findAll());
        model.addAttribute("q", q);
        model.addAttribute("selCategory", category);
        model.addAttribute("selMall", mall);
        return "stores";
    }

    @GetMapping("/admin/catalog")
    public String catalog(Model model) {
        model.addAttribute("malls", malls.findAll());
        model.addAttribute("categories", stores.categories());
        return "admin-catalog";
    }

    @PostMapping("/admin/stores")
    public String addStore(@RequestParam long mallId,
                           @RequestParam(defaultValue = "0") int floor,
                           @RequestParam String category,
                           @RequestParam String name,
                           @RequestParam(required = false) String unitNo,
                           @RequestParam(required = false) String contact,
                           RedirectAttributes flash) {
        try {
            stores.createStore(mallId, floor, category, name, unitNo, contact);
            flash.addFlashAttribute("msg", "Store added: " + name);
        } catch (RuntimeException e) {
            flash.addFlashAttribute("err", e.getMessage());
        }
        return "redirect:/admin/catalog";
    }

    @PostMapping("/admin/import")
    public String importCsv(@RequestParam("file") MultipartFile file, RedirectAttributes flash) {
        if (file.isEmpty()) {
            flash.addFlashAttribute("err", "Choose a CSV file first");
            return "redirect:/admin/catalog";
        }
        try {
            StoreService.ImportResult r = stores.importCsv(file.getInputStream());
            flash.addFlashAttribute("msg", "Imported " + r.imported() + " stores, skipped " + r.skipped());
            if (!r.errors().isEmpty()) {
                flash.addFlashAttribute("err", String.join(" | ", r.errors()));
            }
        } catch (IOException e) {
            flash.addFlashAttribute("err", "Could not read the file: " + e.getMessage());
        }
        return "redirect:/admin/catalog";
    }
}
