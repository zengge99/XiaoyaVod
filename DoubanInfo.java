package com.github.catvod.bean;

public class DoubanInfo {
        private String id;
        private String plot;
        private String year;
        private String region;
        private String actors;
        private String director;
        private String type;
        private String rating;
        private String name;

        // Getter 方法
        public String getId() {
            return id != null ? id : "";
        }
        
        public String getPlot() {
            return plot != null ? plot : "";
        }
    
        public String getYear() {
            return year != null ? year : "";
        }
    
        public String getRegion() {
            return region != null ? region : "";
        }
    
        public String getActors() {
            return actors != null ? actors : "";
        }
    
        public String getDirector() {
            return director != null ? director : "";
        }
    
        public String getType() {
            return type != null ? type : "";
        }
    
        public String getRating() {
            return rating != null ? rating : "";
        }

        public String getName() {
            return name != null ? name : "";
        }
    
        // Setter 方法
        public void setId(String id) {
            this.id = id;
        }
        
        public void setPlot(String plot) {
            this.plot = plot;
        }
    
        public void setYear(String year) {
            this.year = year;
        }
    
        public void setRegion(String region) {
            this.region = region;
        }
    
        public void setActors(String actors) {
            this.actors = actors;
        }
    
        public void setDirector(String director) {
            this.director = director;
        }
    
        public void setType(String type) {
            this.type = type;
        }
    
        public void setRating(String rating) {
            this.rating = rating;
        }
        
        public void setName(String name) {
            this.name = name;
        }
    }
