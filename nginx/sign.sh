#!/bin/bash

# 配置文件路径
CONFIG_FILE="/etc/nginx/http.d/default.conf" 

direct_link=$(cat /opt/alist/alist | grep -ao "\\\$\\{[a-z]*\\}\\\$\\{[a-z]*\\}\\\$\\{[a-z]*\\}" | sed "s/\\\$/\$dollar/g")
direct_link_sign=$direct_link

sign_cond=$(cat /opt/alist/alist | grep -ao '[a-z]*!=="preview"&&[a-z]*\.sign' | sed "s/\\\$/\$dollar/g")
sign_str=$(cat /opt/alist/alist | grep -ao '\?sign=\$\{[a-z]*\.sign\}' | sed "s/\\\$/\$dollar/g")

#这里有两个匹配的，第二处不知道什么应用场景，仅修改第一处。
login_cmd=$(cat /opt/alist/alist | grep -ao '[a-zA-Z]\.success.*?login\.success.*?,.*?.token\),' | head -n1 | sed "s/\\\$/\$dollar/g")
login_cmd_sign=$login_cmd
post_api_auth=$(cat /opt/alist/alist | grep -ao '[a-zA-Z]*\.post\("/auth/login"' | awk -F'(' '{print $1}' | head -n1)

location_getsignmd5=$(cat "$CONFIG_FILE" | grep "cgi-bin/soutv" | sed 's/^\s*//g' | head -n1)

if [ ! -f /data/nosign.txt ] && [ -f /data/guestpass.txt ] && [ -f /data/guestlogin.txt ]; then
    
    if [ -f /data/salt.txt ]; then
        sign=$(cat /data/salt.txt | tr -d '\r\n' | md5sum | awk '{print $1}')
    else
        sign=$({ ip link show; date; } | tr -d '\r\n' | md5sum | awk '{print $1}')
    fi
    
    echo -n "$sign" >/index/md5

    #给URL加上签名
    js='
(function(){
    return "?sign=" + localStorage.getItem("signmd5");
})();
'
    js=$(echo -n "$js" | base64 -w 0)
    direct_link_sign="$direct_link\$dollar{(()=>{const encodedFunc = \"$js\"; const decodedFunc = atob(encodedFunc); return eval(decodedFunc);})()}"

    #登录的同时远程获取md5
    js='
(function(){
    '"$post_api_auth"'("/getsignmd5", "cat md5", {
  headers: {
    "Content-Type": "text/plain"
  }
})
        .then(response => {
            localStorage.setItem("signmd5", response);
        });
})();
'
    js=$(echo -n "$js" | base64 -w 0)
    login_cmd_sign="$login_cmd(()=>{const encodedFunc = \"$js\"; const decodedFunc = atob(encodedFunc); eval(decodedFunc);})(),"

fi

alist_address="http://127.0.0.1:$(cat "$CONFIG_FILE" | grep -o ':[0-9]*/dav' | tr -d ':' | awk -F'/' '{print $1}'| head -n1)"
docker_ip="127.0.0.1"
if [ "$alist_address"x = "http://127.0.0.1:5244"x ]; then
    docker_ip=$(ifconfig "eth0" | grep "inet addr" | grep -o "([0-9]{1,3}\.){3}[0-9]{1,3}" | head -n1)
fi

sed -i "s/sign=[^']*/sign=$sign/g" /etc/nginx/http.d/emby.js

gen_api_get_list_sub_filter() {
    while read -r line; do
        post_api=$(echo "$line" | awk -F'(' '{print $1}')
        js='
(function(){
    '"$post_api"'("/getsignmd5", "cat md5", {
  headers: {
    "Content-Type": "text/plain"
  }
})
        .then(response => {
            localStorage.setItem("signmd5", response);
        });
})();
'
        js=$(echo -n "$js" | base64 -w 0)
        sub_line="(()=>{(()=>{const encodedFunc = \"$js\"; const decodedFunc = atob(encodedFunc); eval(decodedFunc);})();return $line})()"
        echo 'sub_filter '"'$line'"' '"'$sub_line'"';'
    done < <(
        cat /opt/alist/alist | grep -ao '[a-zA-Z]*\.post\("/fs/get".*?\)'
        cat /opt/alist/alist | grep -ao '[a-zA-Z]*\.post\("/fs/list".*?\)'
        )
}

config_geo() {
    sign=$1
    if [ -z "$sign" ]; then
        NEW_CONFIG=''
    else
        NEW_CONFIG='
geo $remote_addr $is_external {
    default 1;

}
'
    fi

    # 临时文件
    TMP_FILE=$(mktemp)

    # 检查文件是否存在
    if [ ! -f "$CONFIG_FILE" ]; then
        echo "Error: Nginx configuration file not found at $CONFIG_FILE"
        exit 1
    fi

    # 处理文件
    awk -v new_config="$NEW_CONFIG" '
    BEGIN {
        print
        # 立即插入新配置
        printf("%s",new_config)
    }

    # 在目标块中检查现有的geo块
    /geo \$remote_addr \$is_external/ {
        # 跳过现有的块（不打印）
        while (getline > 0) {
            if (/}[[:space:]]*$/) {
                break
            }
        }
        next
    }

    # 其他情况直接打印
    {
        print
    }
    ' "$CONFIG_FILE" | sed '/^[[:space:]]*$/N; /^\n$/D' > "$TMP_FILE"

    mv -f "$TMP_FILE" "$CONFIG_FILE"

}

config_uri_map() {
    sign=$1
    if [ -z "$sign" ]; then
        NEW_CONFIG=''
    else
        NEW_CONFIG='
map $is_external $modified_uri {
    default         $uri?sign=$arg_sign;
    0               $uri?sign='"$sign"';
    1               $uri?sign=$arg_sign;
}
'
    fi

    # 临时文件
    TMP_FILE=$(mktemp)

    # 检查文件是否存在
    if [ ! -f "$CONFIG_FILE" ]; then
        echo "Error: Nginx configuration file not found at $CONFIG_FILE"
        exit 1
    fi

    # 处理文件
    awk -v new_config="$NEW_CONFIG" '
    BEGIN {
        print
        # 立即插入新配置
        printf("%s",new_config)
    }

    # 在目标块中检查现有的map块
    /map \$is_external \$modified_uri/ {
        # 跳过现有的块（不打印）
        while (getline > 0) {
            if (/}[[:space:]]*$/) {
                break
            }
        }
        next
    }

    # 其他情况直接打印
    {
        print
    }
    ' "$CONFIG_FILE" | sed '/^[[:space:]]*$/N; /^\n$/D' > "$TMP_FILE"

    mv -f "$TMP_FILE" "$CONFIG_FILE"

}

config_geo_dollar() {
    sign=$1
    if [ -z "$sign" ]; then
        NEW_CONFIG=''
    else
        NEW_CONFIG='
geo $dollar {
    default "$";
}
'
    fi

    # 临时文件
    TMP_FILE=$(mktemp)

    # 检查文件是否存在
    if [ ! -f "$CONFIG_FILE" ]; then
        echo "Error: Nginx configuration file not found at $CONFIG_FILE"
        exit 1
    fi

    # 处理文件
    awk -v new_config="$NEW_CONFIG" '
    BEGIN {
        print
        # 立即插入新配置
        printf("%s",new_config)
    }

    # 在目标块中检查现有的geo块
    /geo \$dollar/ {
        # 跳过现有的块（不打印）
        while (getline > 0) {
            if (/}[[:space:]]*$/) {
                break
            }
        }
        next
    }

    # 其他情况直接打印
    {
        print
    }
    ' "$CONFIG_FILE" | sed '/^[[:space:]]*$/N; /^\n$/D' > "$TMP_FILE"

    mv -f "$TMP_FILE" "$CONFIG_FILE"

}

config_location_assets() {
    sign=$1
    if [ -z "$sign" ]; then
        NEW_CONFIG=''
    else
        NEW_CONFIG='
    location /assets {
        proxy_pass '"$alist_address"';
        proxy_set_header Accept-Encoding "";
        sub_filter '"'$direct_link'"' '"'$direct_link_sign'"';
        sub_filter '"'$login_cmd'"' '"'$login_cmd_sign'"';
        sub_filter '"'$sign_str'"' '"''"';
        sub_filter '"'$sign_cond'"' '"'false'"';
        '"$(gen_api_get_list_sub_filter)"'
        sub_filter_once off;
        sub_filter_types *;
        proxy_cache apicache;
    }
'
    fi

    # 临时文件
    TMP_FILE=$(mktemp)

    # 检查文件是否存在
    if [ ! -f "$CONFIG_FILE" ]; then
        echo "Error: Nginx configuration file not found at $CONFIG_FILE"
        exit 1
    fi

    # 处理文件
    awk -v new_config="$NEW_CONFIG" '
    BEGIN {
        in_target_block = 0
    }

    # 匹配到目标server行时立即插入配置
    /server \{/ {
        print
        # 立即插入新配置
        printf("%s",new_config)
        in_target_block = 1
        next
    }

    # 在目标块中检查现有的location /assets
    in_target_block && /location \/assets/ {
        # 跳过现有的if块（不打印）
        while (getline > 0) {
            if (/}[[:space:]]*$/) {
                break
            }
        }
	in_target_block = 0
        next
    }

    # 其他情况直接打印
    {
        print
    }
    ' "$CONFIG_FILE" | sed '/^[[:space:]]*$/N; /^\n$/D' > "$TMP_FILE"

    mv -f "$TMP_FILE" "$CONFIG_FILE"

}

config_location_getsignmd5() {
    sign=$1
    if [ -z "$sign" ]; then
        NEW_CONFIG=''
    else
        NEW_CONFIG='
    location /api/getsignmd5 {
        '"$location_getsignmd5"'
    }
'
    fi

    # 临时文件
    TMP_FILE=$(mktemp)

    # 检查文件是否存在
    if [ ! -f "$CONFIG_FILE" ]; then
        echo "Error: Nginx configuration file not found at $CONFIG_FILE"
        exit 1
    fi

    # 处理文件
    awk -v new_config="$NEW_CONFIG" '
    BEGIN {
        in_target_block = 0
    }

    # 匹配到目标server行时立即插入配置
    /server \{/ {
        print
        # 立即插入新配置
        printf("%s",new_config)
        in_target_block = 1
        next
    }

    # 在目标块中检查现有的location /assets
    in_target_block && /location \/api\/getsignmd5/ {
        # 跳过现有的if块（不打印）
        while (getline > 0) {
            if (/}[[:space:]]*$/) {
                break
            }
        }
	in_target_block = 0
        next
    }

    # 其他情况直接打印
    {
        print
    }
    ' "$CONFIG_FILE" | sed '/^[[:space:]]*$/N; /^\n$/D' > "$TMP_FILE"

    mv -f "$TMP_FILE" "$CONFIG_FILE"

}

config_emby_local_direct_link_server() {
    sign=$1
    if [ -z "$sign" ]; then
        NEW_CONFIG=''
    else
        NEW_CONFIG='
server  {
    listen '"$docker_ip"':45678;
    server_name emby_local_direct_link;
    location  /d/ {
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Host $http_host;
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header Range $http_range;
        proxy_set_header If-Range $http_if_range;
        proxy_redirect off;
        proxy_pass '"$alist_address"'/d/;
        limit_req zone=two burst=2;
    }
}
'
    fi

    TMP_FILE=$(mktemp)

    if [ ! -f "$CONFIG_FILE" ]; then
        echo "Error: Nginx configuration file not found at $CONFIG_FILE"
        exit 1
    fi

    awk -v new_config="$NEW_CONFIG" '
    BEGIN {
        if (new_config != "") {
            print new_config
        }
    }

    /server  \{/ {
        depth = 0
        depth += (gsub(/\{/, "{", $0) - gsub(/\}/, "}", $0))
        while (depth > 0 && (getline > 0)) {
            depth += (gsub(/\{/, "{", $0) - gsub(/\}/, "}", $0))
        }
        next
    }

    {
        print
    }
    ' "$CONFIG_FILE" | sed '/^[[:space:]]*$/N; /^\n$/D' > "$TMP_FILE"

    mv -f "$TMP_FILE" "$CONFIG_FILE"
}

config_block_p() {
    NEW_CONFIG='
    location ^~ /p/ {
        deny all;
        return 403;
    }
'

    # 临时文件
    TMP_FILE=$(mktemp)

    # 检查文件是否存在
    if [ ! -f "$CONFIG_FILE" ]; then
        echo "Error: Nginx configuration file not found at $CONFIG_FILE"
        exit 1
    fi

    # 处理文件
    awk -v new_config="$NEW_CONFIG" '
    BEGIN {
        in_target_block = 0
    }

    # 匹配到目标server行时立即插入配置
    /server \{/ {
        print
        # 立即插入新配置
        printf("%s",new_config)
        in_target_block = 1
        next
    }

    # 在目标块中检查现有的location
    in_target_block && /location \^\~ \/p\/ \{/ {
        # 跳过现有的if块（不打印）
        while (getline > 0) {
            if (/}[[:space:]]*$/) {
                break
            }
        }
	in_target_block = 0
        next
    }

    # 其他情况直接打印
    {
        print
    }
    ' "$CONFIG_FILE" | sed '/^[[:space:]]*$/N; /^\n$/D' > "$TMP_FILE"

    mv -f "$TMP_FILE" "$CONFIG_FILE"
}

config_strm() {
    sign=$1
    NEW_CONFIG='
    location ^~ /dav/strm {
        proxy_pass '"$alist_address"'/dav/strm;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Host $http_host;
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header Range $http_range;
        proxy_set_header If-Range $http_if_range;
        proxy_set_header Accept-Encoding "";
        proxy_hide_header Content-Disposition;
        proxy_redirect off;
        proxy_cache apicache;
        sub_filter_once off;
        sub_filter http://xiaoya.host:5678 http://$http_host;
        sub_filter SIGN_STR "'"$sign"'";
        sub_filter_types text/plain;
    }
'

    # 临时文件
    TMP_FILE=$(mktemp)

    # 检查文件是否存在
    if [ ! -f "$CONFIG_FILE" ]; then
        echo "Error: Nginx configuration file not found at $CONFIG_FILE"
        exit 1
    fi

    # 处理文件
    awk -v new_config="$NEW_CONFIG" '
    BEGIN {
        in_target_block = 0
    }

    # 匹配到目标server行时立即插入配置
    /server \{/ {
        print
        # 立即插入新配置
        printf("%s",new_config)
        in_target_block = 1
        next
    }

    # 在目标块中检查现有的location
    in_target_block && /location \^\~ \/dav\/strm \{/ {
        # 跳过现有的块（不打印）
        while (getline > 0) {
            if (/}[[:space:]]*$/) {
                break
            }
        }
	in_target_block = 0
        next
    }

    # 其他情况直接打印
    {
        print
    }
    ' "$CONFIG_FILE" | sed '/^[[:space:]]*$/N; /^\n$/D' > "$TMP_FILE"

    mv -f "$TMP_FILE" "$CONFIG_FILE"
}

config_strm_lua() {
    sign=$1
    NEW_CONFIG='
    location ^~ /dav/strm {
        proxy_pass '"$alist_address"'/dav/strm;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Host $http_host;
        proxy_set_header Host $http_host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header Range $http_range;
        proxy_set_header If-Range $http_if_range;
        proxy_set_header Accept-Encoding "";
        proxy_hide_header Content-Disposition;
        header_filter_by_lua_block {
            ngx.header.content_length = nil
        }
        body_filter_by_lua_block {
            local raw_host_str = "http://xiaoya.host:5678"
            local raw_sign_str = "SIGN_STR"
            local new_sign_str = "'"$sign"'"
            local cur_host = ngx.var.client_scheme .. "://" .. (ngx.var.http_host or "")
            if ngx.arg[1] ~= "" then
                local data = ngx.arg[1]
                local total_diff = (#cur_host - #raw_host_str) + (#new_sign_str - #raw_sign_str)
                data = string.gsub(data, raw_host_str, cur_host)
                data = string.gsub(data, raw_sign_str, new_sign_str)
                data = string.gsub(data, "(##+)", function(m)
                    if #m >= 30 then
                        local new_count = #m - total_diff
                        return string.rep("#", math.max(0, new_count))
                    end
                    return m
                end)
                ngx.arg[1] = data
            end
        }
    }
'

    TMP_FILE=$(mktemp)

    if [ ! -f "$CONFIG_FILE" ]; then
        echo "Error: Nginx configuration file not found at $CONFIG_FILE"
        exit 1
    fi

    awk -v new_config="$NEW_CONFIG" '
    BEGIN {
        in_target_block = 0
    }

    # 匹配到目标server行时立即插入新配置
    /server \{/ {
        print
        printf("%s", new_config)
        in_target_block = 1
        next
    }

    # 在目标server块中匹配到旧的 location ^~ /dav/strm {
    in_target_block && /location \^\~ \/dav\/strm \{/ {
        depth = 0
        # 计算当前行括号差值
        depth += (gsub(/\{/, "{", $0) - gsub(/\}/, "}", $0))
        
        # 只要括号没配对完（depth > 0），就持续读取并跳过
        while (depth > 0 && (getline > 0)) {
            depth += (gsub(/\{/, "{", $0) - gsub(/\}/, "}", $0))
        }
        
        in_target_block = 0
        next
    }

    # 其他情况原样打印
    {
        print
    }
    ' "$CONFIG_FILE" | sed '/^[[:space:]]*$/N; /^\n$/D' > "$TMP_FILE"

    mv -f "$TMP_FILE" "$CONFIG_FILE"
}

config_danmu_api() {
    DANMU_API="DANMU_API"
    if [ -f /data/danmu_api.txt ];then
        DANMU_API=$(cat /data/danmu_api.txt)
    fi
    NEW_CONFIG='
            location /tvbox {
                proxy_pass '"$(cat "$CONFIG_FILE" | grep -o "http://([0-9]{1,3}\.){3}[0-9]{1,3}(:[0-9]{1,5})?/tvbox" | head -n1)"';
                proxy_set_header Accept-Encoding "";
                sub_filter "DOCKER_ADDRESS" $client_scheme://$http_host;
                sub_filter "DANMU_API" '"$DANMU_API"';
                sub_filter_once off;
                sub_filter_types *;
                proxy_cache apicache;
            }
'

    # 临时文件
    TMP_FILE=$(mktemp)

    # 检查文件是否存在
    if [ ! -f "$CONFIG_FILE" ]; then
        echo "Error: Nginx configuration file not found at $CONFIG_FILE"
        exit 1
    fi

    # 处理文件
    awk -v new_config="$NEW_CONFIG" '
    BEGIN {
        in_target_block = 0
    }

    # 匹配到目标server行时立即插入配置
    /server \{/ {
        print
        # 立即插入新配置
        printf("%s",new_config)
        in_target_block = 1
        next
    }

    # 在目标块中检查现有的location /assets
    in_target_block && /location \/tvbox/ {
        # 跳过现有的if块（不打印）
        while (getline > 0) {
            if (/}[[:space:]]*$/) {
                break
            }
        }
	in_target_block = 0
        next
    }

    # 其他情况直接打印
    {
        print
    }
    ' "$CONFIG_FILE" | sed '/^[[:space:]]*$/N; /^\n$/D' > "$TMP_FILE"

    mv -f "$TMP_FILE" "$CONFIG_FILE"

}

config_location_lua() {
    sign=$1
    if [ -z "$sign" ]; then
        NEW_CONFIG='
        location /d/ {
            set $fixed_uri "";
            rewrite_by_lua_block {
                local raw_uri = ngx.var.request_uri
                if string.find(raw_uri, "%%25%x%x") then
                    raw_uri = string.gsub(raw_uri, "%%25(%x%x)", "%%%1")
                end
                ngx.var.fixed_uri = raw_uri
            }
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header Host $http_host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header Range $http_range;
            proxy_set_header If-Range $http_if_range;
            proxy_pass '"$alist_address"'$fixed_uri;
        }
'
    else
        NEW_CONFIG='
        location /d/ {
            set $fixed_uri "";
            rewrite_by_lua_block {
                local raw_uri = ngx.var.request_uri
                if string.find(raw_uri, "%%25%x%x") then
                    raw_uri = string.gsub(raw_uri, "%%25(%x%x)", "%%%1")
                end
                ngx.var.fixed_uri = raw_uri
                local args = ngx.var.args or ""
                local target_sign = "'"$sign"'"
                if not string.find(args, "sign=" .. target_sign) then
                    return ngx.redirect("http://'"$docker_ip"':45678" .. ngx.var.request_uri, 302)
                end
            }
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header Host $http_host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header Range $http_range;
            proxy_set_header If-Range $http_if_range;
            proxy_pass '"$alist_address"'$fixed_uri;
        }
'
    fi

    TMP_FILE=$(mktemp)

    if [ ! -f "$CONFIG_FILE" ]; then
        exit 1
    fi

    awk -v new_config="$NEW_CONFIG" '
    BEGIN {
        in_target_block = 0
    }
    /server \{/ {
        print
        printf("%s", new_config)
        in_target_block = 1
        next
    }
    in_target_block && /location \/d\/ \{/ {
        depth = 0
        depth += (gsub(/\{/, "{", $0) - gsub(/\}/, "}", $0))
        while (depth > 0 && (getline > 0)) {
            depth += (gsub(/\{/, "{", $0) - gsub(/\}/, "}", $0))
        }
        in_target_block = 0
        next
    }
    {
        print
    }
    ' "$CONFIG_FILE" | sed '/^[[:space:]]*$/N; /^\n$/D' > "$TMP_FILE"

    mv -f "$TMP_FILE" "$CONFIG_FILE"
}

config_location_lua "$sign"
config_uri_map "$sign"
config_geo "$sign"

#给网页直链加上签名
config_geo_dollar "$sign"
config_location_assets "$sign"

#给nginx放开获取signmd5的api白名单
config_location_getsignmd5 "$sign"

#增加emby本地直链服务器
config_emby_local_direct_link_server "$sign"

#不允许访问/p/，没有带签名匿名访问有安全隐患
config_block_p

#给strm文件内容加上签名
config_strm_lua "$sign"

#弹幕API替换
config_danmu_api

if [ -f /run/nginx/nginx.pid ]; then
    nginx -s reload
fi
