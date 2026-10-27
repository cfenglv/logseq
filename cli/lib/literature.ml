open Cli_effect.Infix

type kind = List_papers | Search_papers | Show_paper | Import_papers | Update_paper | Attach_pdf

type parsed = {
  kind : kind;
  uuid : string option;
  content : string option;
  input : string option;
  stdin : string option;
  target_page : string option;
  asset_uuid : string option;
  path : string option;
  dry_run : bool;
  limit : int option;
  before : int64 option;
}

type action = {
  repo : Cli_primitive.repo;
  request : parsed;
  records : Melange_edn_melange.any Rrbvec.t;
}

let kind_of_string = function
  | "list" -> Some List_papers | "search" -> Some Search_papers
  | "show" -> Some Show_paper | "import" -> Some Import_papers
  | "update" -> Some Update_paper | "attach" -> Some Attach_pdf | _ -> None

let command_id p = match p.kind with
  | List_papers -> Command_id.Literature_list | Search_papers -> Literature_search
  | Show_paper -> Literature_show | Import_papers -> Literature_import
  | Update_paper -> Literature_update | Attach_pdf -> Literature_attach

let kw = Edn_util.keyword
let string = Edn_util.string
let map fields = Edn_util.map_vec (Vec.of_array fields)
let get value key = Option.value (Edn_util.get value key) ~default:Edn_util.nil
let text value key = Option.value (Edn_util.get_string value key) ~default:""
let seq value = Option.value (Edn_util.as_seq value) ~default:Vec.empty
let uuid_value value = Option.bind value Edn_util.as_string_like
let result_status value = text value "status"
let edn source = Melange_edn_melange.of_edn_string source

let validate_parsed p =
  let required label = function
    | Some value when String.trim value <> "" -> Ok ()
    | _ -> Error (Error.invalid_options (label ^ " is required"))
  in
  let valid_uuid label = function
    | Some value when Cli_primitive.is_uuid_string value -> Ok ()
    | _ -> Error (Error.invalid_options (label ^ " must be a UUID"))
  in
  match p.kind with
  | List_papers ->
      if Option.fold ~none:false ~some:(fun n -> n < 1 || n > 50) p.limit then
        Error (Error.invalid_options "limit must be between 1 and 50")
      else if Option.fold ~none:false ~some:(fun n -> n <= 0L) p.before then
        Error (Error.invalid_options "before must be a positive database id")
      else Ok ()
  | Search_papers -> required "content" p.content
  | Show_paper -> valid_uuid "uuid" p.uuid
  | Import_papers -> Error.bind (required "input" p.input) (fun () -> required "target-page" p.target_page)
  | Update_paper -> Error.bind (required "input" p.input) (fun () -> valid_uuid "uuid" p.uuid)
  | Attach_pdf ->
      Error.bind (valid_uuid "uuid" p.uuid) (fun () -> match p.asset_uuid, p.path with
        | Some asset, None -> valid_uuid "asset-uuid" (Some asset)
        | None, Some path ->
            if String.lowercase_ascii (Filename.extension path) <> ".pdf" then
              Error (Error.invalid_options "attach requires a PDF file")
            else if not (Cli_unix.file_exists path) then
              Error (Error.make Error.Asset_file_not_found "asset file not found")
            else Ok ()
        | _ -> Error (Error.invalid_options "use exactly one of --asset-uuid or --path"))

let metadata_fields = [| "title"; "authors"; "date"; "publication"; "type"; "doi"; "arxiv"; "url"; "citekey" |]

let normalize_record ~import value =
  match Edn_util.as_map value with
  | None -> Error (Error.invalid_options "each literature record must be a JSON object")
  | Some fields ->
      let rec loop result rest = match Vec.pop_front rest with
        | None -> Ok (Edn_util.map_vec result)
        | Some ((key, value), remaining) ->
            let name = Option.value (Edn_util.as_string key) ~default:"" in
            if name = "uuid" && import then
              (match Edn_util.as_string value with
               | Some uuid when Cli_primitive.is_uuid_string uuid -> loop (Vec.push_back result (kw name, Edn_util.uuid uuid)) remaining
               | _ -> Error (Error.invalid_options "record uuid must be a UUID string"))
            else if not (Array.mem name metadata_fields) then
              Error (Error.invalid_options ("unknown literature field: " ^ name))
            else if not (Edn_util.is_null value || Option.is_some (Edn_util.as_string value)) then
              Error (Error.invalid_options (name ^ " must be a string or null"))
            else if name = "title" && String.trim (Option.value (Edn_util.as_string value) ~default:"") = "" then
              Error (Error.invalid_options "title cannot be empty")
            else loop (Vec.push_back result (kw name, value)) remaining
      in
      Error.bind (loop Vec.empty fields) (fun record ->
        if import && String.trim (text record "title") = "" then Error (Error.invalid_options "import requires a title")
        else Ok record)

let read_records p =
  if p.kind <> Import_papers && p.kind <> Update_paper then Ok Vec.empty else
  try
    let source = match p.input with
      | Some "-" -> Option.value p.stdin ~default:""
      | Some path -> Cli_unix.read_text_file path
      | None -> assert false
    in
    let value = Json_util.value_of_json_string source in
    let records = match Edn_util.as_map value, Edn_util.as_vector value with
      | Some _, _ -> Vec.singleton value
      | _, Some records when p.kind = Import_papers -> records
      | _ -> Vec.empty
    in
    if Vec.is_empty records then Error (Error.invalid_options "input must contain a JSON record or non-empty import array")
    else
      Vec.fold_left (fun result value -> Error.bind result (fun records ->
          Error.map (fun record -> Vec.push_back records record) (normalize_record ~import:(p.kind = Import_papers) value))) (Ok Vec.empty) records
  with exn -> Error (Error.invalid_options ("cannot read literature JSON: " ^ Printexc.to_string exn))

let build ?registry:_ config _globals request =
  Error.bind (validate_parsed request) (fun () ->
    Error.bind (read_records request) (fun records ->
      match config.Cli_config.repo with
      | None -> Error (Error.missing_repo "graph is required for literature")
      | Some repo -> Ok {repo; request; records}))

let hidden_rules = edn "[[(literature-hidden ?e) [?e :logseq.property/hide? true]] [(literature-hidden ?e) [?e :logseq.property/deleted-at _]] [(literature-hidden ?e) [?e :block/parent ?parent] (literature-hidden ?parent)]]"

let record_selector =
  "[:db/id :block/uuid :block/title :logseq.property/literature-item? :logseq.property/deleted-at :logseq.property/hide? \
   {:block/parent ...} \
   {:logseq.property/literature-authors [:block/title]} {:logseq.property/literature-date [:block/title]} \
   {:logseq.property/literature-publication [:block/title]} {:logseq.property/literature-type [:block/title]} \
   {:logseq.property/literature-doi [:block/title]} {:logseq.property/literature-arxiv [:block/title]} \
   {:logseq.property/literature-url [:block/title]} {:logseq.property/literature-citekey [:block/title]} \
   {:logseq.property/literature-assets [:block/uuid :block/title :logseq.property.asset/type \
     :logseq.property.asset/checksum :logseq.property.asset/remote-metadata {:logseq.property/literature-aliases [:block/title]} \
     :logseq.property/deleted-at :logseq.property/hide? {:block/parent ...}]}]"

let query invoke repo source inputs =
  Transport.thread_api_q invoke ~repo ~query:(Edn_util.vector_t_vec (Vec.push_front inputs (edn source)))

let read_paper invoke repo uuid =
  query invoke repo
    ("[:find (pull ?paper " ^ record_selector ^ ") . :in $ ?uuid % :where [?paper :block/uuid ?uuid] [?paper :logseq.property/literature-item? true] (not-join [?paper] (literature-hidden ?paper))]")
    (Vec.of_array [| Edn_util.uuid uuid; hidden_rules |])

let asset_value ~files config repo asset =
  let uuid = text asset "block/uuid" in
  let kind = text asset "logseq.property.asset/type" in
  let local_path = Filename.concat (Upsert.graph_assets_dir config repo) (uuid ^ "." ^ kind) in
  let local = files && Cli_unix.file_exists local_path in
  let remote = not (Edn_util.is_null (get asset "logseq.property.asset/remote-metadata")) in
  map [| kw "uuid", string uuid; kw "title", get asset "block/title";
         kw "filenames", Edn_util.vector_vec (seq (get asset "logseq.property/literature-aliases") |> Vec.map (fun value -> get value "block/title"));
         kw "checksum", get asset "logseq.property.asset/checksum";
         kw "local-path", (if local then string local_path else Edn_util.nil);
         kw "local-status", string (if local then "available" else if not files then "not-checked" else if remote then "remote-only" else "missing");
         kw "hint", (if files && not local && remote then string ("logseq sync asset download --graph " ^ Melange_edn_melange.to_edn_string (string (Cli_primitive.string_of_graph (Cli_config.repo_to_graph repo))) ^ " --uuid " ^ uuid) else Edn_util.nil) |]

let paper_value ~files config repo paper =
  let fields = Vec.of_array [| kw "id", get paper "db/id"; kw "uuid", string (text paper "block/uuid"); kw "title", get paper "block/title" |] in
  let fields = Array.fold_left (fun fields name ->
      if name = "title" then fields else Vec.push_back fields (kw name, get (get paper ("logseq.property/literature-" ^ name)) "block/title")) fields metadata_fields in
  let assets = seq (get paper "logseq.property/literature-assets")
    |> Vec.filter (fun asset -> text asset "logseq.property.asset/type" = "pdf" && not (Search.hidden asset))
    |> Vec.map (asset_value ~files config repo) in
  Edn_util.map_vec (Vec.push_back fields (kw "assets", Edn_util.vector_vec assets))

let error_result command mode context =
  let reason = text context "reason" in
  let message = if reason = "" then "Literature operation did not complete" else "Literature operation blocked: " ^ reason in
  let detail = text context "detail" in
  let message = if detail = "" then message else message ^ ": " ^ detail in
  Cli_result.error ~command mode (Error.make ~context Error.Literature_operation_blocked message)

let success command mode value = Cli_result.ok ~command mode (Cli_result.Raw value)
let completed value = List.mem (result_status value) ["created"; "updated"; "planned"; "unchanged"; "existing"; "linked"; "already-linked"]

let execute_list command mode config invoke action =
  let count = Option.value action.request.limit ~default:50 in
  let before = Option.value action.request.before ~default:9_007_199_254_740_991L in
  query invoke action.repo
    (Printf.sprintf "[:find (max %d ?paper) :in $ ?before %% :where [?paper :logseq.property/literature-item? true] [?paper :block/uuid _] [(< ?paper ?before)] (not-join [?paper] (literature-hidden ?paper))]" (count + 1))
    (Vec.of_array [| Edn_util.int64 before; hidden_rules |]) >>= fun result ->
  let ids = match Vec.nth_opt (seq result) 0 with
    | Some row -> (match Vec.nth_opt (seq row) 0 with Some values -> seq values | None -> Vec.empty)
    | None -> Vec.empty in
  let ids = Vec.to_array ids in
  Array.sort (fun a b -> compare (Edn_util.as_int64 b) (Edn_util.as_int64 a)) ids;
  let more = Array.length ids > count in
  let selected = Vec.of_array (Array.sub ids 0 (min count (Array.length ids))) in
  if Vec.is_empty selected then Cli_effect.pure (success command mode (map [|kw "items", Edn_util.vector_vec Vec.empty; kw "has-more", Edn_util.bool false; kw "next-before", Edn_util.nil|]))
  else query invoke action.repo
    ("[:find [(pull ?paper " ^ record_selector ^ ") ...] :in $ [?paper ...] % :where [?paper :logseq.property/literature-item? true] (not-join [?paper] (literature-hidden ?paper))]")
    (Vec.of_array [| Edn_util.vector_vec selected; hidden_rules |]) >>= fun records ->
    let records = Vec.to_array (seq records) in
    Array.sort (fun a b -> compare (Edn_util.as_int64 (get b "db/id")) (Edn_util.as_int64 (get a "db/id"))) records;
    let items = Vec.of_array records |> Vec.map (paper_value ~files:false config action.repo) in
    Cli_effect.pure (success command mode (map [|kw "items", Edn_util.vector_vec items; kw "has-more", Edn_util.bool more;
      kw "next-before", (if more then Vec.nth selected (Vec.length selected - 1) else Edn_util.nil)|]))

let execute_show command mode config invoke action =
  let uuid = Option.get action.request.uuid in
  read_paper invoke action.repo uuid >>= fun paper ->
  Cli_effect.pure (if Edn_util.is_null paper then error_result command mode (map [|kw "reason", kw "paper-unavailable"; kw "paper-uuid", string uuid|])
    else success command mode (paper_value ~files:true config action.repo paper))

let execute_search command mode config invoke action =
  Transport.thread_api_literature_search invoke ~repo:action.repo ~content:(Option.get action.request.content) >>= fun result ->
  if result_status result <> "ok" then Cli_effect.pure (error_result command mode result)
  else
    Cli_effect.map_s (fun item -> read_paper invoke action.repo (text item "block/uuid")) (seq (get result "papers")) >>= fun records ->
    let items = records |> Vec.filter (fun x -> not (Edn_util.is_null x)) |> Vec.map (paper_value ~files:false config action.repo) in
    Cli_effect.pure (success command mode (map [|kw "items", Edn_util.vector_vec items; kw "truncated", get result "truncated?"|]))

let destination_page invoke repo title =
  let selector = Edn_util.vector_t_vec (Vec.of_array [|kw "block/uuid"; kw "block/title"; kw "logseq.property/hide?"; kw "logseq.property/deleted-at"; edn "{:block/parent ...}"|]) in
  Transport.thread_api_pull invoke ~repo ~selector ~lookup:(edn ("[:block/name " ^ Melange_edn_melange.to_edn_string (string title) ^ "]"))

let ensure_destination config invoke repo title =
  destination_page invoke repo title >>= fun page ->
  if not (Edn_util.is_null page) then Cli_effect.pure (Ok page)
  else
    let options : Upsert.page_opts = {id=None; page=Some title; restore=false; update_tags_edn=None; remove_tags_edn=None; update_properties_edn=None; remove_properties_edn=None} in
    match Upsert.build config (Global_opts.create ()) (Upsert.Parsed_page options) with
    | Error err -> Cli_effect.pure (Error err)
    | Ok plan -> Upsert.execute plan config >>= fun result ->
        if Cli_result.is_error result then Cli_effect.pure (Error (Option.get result.error))
        else destination_page invoke repo title >>= fun page -> Cli_effect.pure (Ok page)

let execute_import command mode config invoke action =
  let title = Option.get action.request.target_page in
  destination_page invoke action.repo title >>= fun page ->
      let destination = ref (uuid_value (Edn_util.get page "block/uuid")) in
      if Search.hidden page then Cli_effect.pure (error_result command mode (map [|kw "reason", kw "destination-unavailable"|])) else
      let rec loop index items remaining = match Vec.pop_front remaining with
        | None ->
            let value = map [|kw "items", Edn_util.vector_vec items|] in
            Cli_effect.pure (if Vec.for_all completed items then success command mode value
              else Cli_result.error ~command mode (Error.make ~context:value Error.Literature_partial_import "Some literature records were not imported; inspect items before retrying"))
        | Some (record, rest) ->
            let call = match !destination with
              | Some uuid -> Transport.thread_api_literature_import invoke ~repo:action.repo ~destination:(Edn_util.uuid uuid) ~record ~dry_run:action.request.dry_run
              | None ->
                  Transport.thread_api_literature_import invoke ~repo:action.repo ~destination:Edn_util.nil ~record ~dry_run:true >>= fun preview ->
                  if result_status preview <> "blocked" || text preview "reason" <> "destination-unavailable" then Cli_effect.pure preview
                  else if action.request.dry_run then
                    Cli_effect.pure (map [|kw "status", kw "planned";kw "target-page", string title;kw "create-page", Edn_util.bool true|])
                  else ensure_destination config invoke action.repo title >>= function
                    | Error err -> Cli_effect.pure (map [|kw "status", kw "blocked";kw "reason", kw "destination-unavailable";kw "message", string err.Error.message|])
                    | Ok page ->
                        destination := uuid_value (Edn_util.get page "block/uuid");
                        (match !destination with
                         | Some uuid -> Transport.thread_api_literature_import invoke ~repo:action.repo ~destination:(Edn_util.uuid uuid) ~record ~dry_run:false
                         | None -> Cli_effect.pure (map [|kw "status", kw "blocked";kw "reason", kw "destination-unavailable"|])) in
            let call = Cli_effect.catch call (fun exn -> Cli_effect.pure (map [|kw "status", kw "blocked";kw "reason", kw "request-failed";kw "message", string (Printexc.to_string exn)|])) in
            call >>= fun result ->
            let fields = Option.get (Edn_util.as_map result) in
            let item = Edn_util.map_vec (Vec.push_back fields (kw "input-index", Edn_util.int index)) in
            loop (index + 1) (Vec.push_back items item) rest
      in loop 0 Vec.empty action.records

let execute_update command mode invoke action =
  Transport.thread_api_literature_update invoke ~repo:action.repo ~uuid:(Option.get action.request.uuid)
    ~patch:(Vec.nth action.records 0) ~dry_run:action.request.dry_run >>= fun result ->
  Cli_effect.pure (if completed result then success command mode result else error_result command mode result)

let link_asset command mode invoke action uuid =
  Transport.thread_api_literature_link invoke ~repo:action.repo ~paper_uuid:(Option.get action.request.uuid) ~asset_uuid:uuid >>= fun result ->
  let fields = Option.get (Edn_util.as_map result) in
  let result = Edn_util.map_vec (Vec.push_back (Vec.push_back fields
    (kw "paper-uuid", string (Option.get action.request.uuid))) (kw "asset-uuid", string uuid)) in
  Cli_effect.pure (if completed result then success command mode result else error_result command mode result)

let execute_attach command mode config invoke action =
  let paper_uuid = Option.get action.request.uuid in
  Transport.thread_api_db_sync_repair_status invoke ~repo:action.repo >>= fun repair ->
  if not (Edn_util.is_null (get repair "operation")) then
    Cli_effect.pure (error_result command mode (map [|kw "status", kw "deferred";kw "reason", kw "repair-active";kw "paper-uuid", string paper_uuid|]))
  else
  read_paper invoke action.repo paper_uuid >>= fun paper ->
  if Edn_util.is_null paper then Cli_effect.pure (error_result command mode (map [|kw "reason", kw "paper-unavailable"|])) else
  match action.request.asset_uuid, action.request.path with
  | Some uuid, None -> link_asset command mode invoke action uuid
  | None, Some path ->
      (match Upsert.ensure_asset_file_metadata path with
       | Error err -> Cli_effect.pure (Cli_result.error ~command mode err)
       | Ok (_, _, checksum) ->
           Transport.thread_api_literature_reusable_pdf invoke ~repo:action.repo ~checksum >>= fun existing ->
           let attach uuid =
             Cli_effect.catch
               (Transport.thread_api_literature_pdf_filename invoke ~repo:action.repo ~asset_uuid:uuid ~filename:(Filename.basename path) >>= fun _ ->
                link_asset command mode invoke action uuid)
               (fun exn -> Cli_effect.pure (error_result command mode (map [|
                 kw "reason", kw "request-failed"; kw "detail", string (Printexc.to_string exn);
                 kw "paper-uuid", string paper_uuid; kw "asset-uuid", string uuid |]))) in
           match result_status existing with
           | "found" -> attach (text (Vec.nth (seq (get existing "assets")) 0) "block/uuid")
           | "missing" ->
               let options : Upsert.asset_opts = {id=None;uuid=None;path=Some path;target_id=None;target_uuid=Some paper_uuid;target_page=None;pos=Some Block.Last_child;content=Some (Filename.basename path)} in
               (match Upsert.build config (Global_opts.create ()) (Upsert.Parsed_asset options) with
                | Error err -> Cli_effect.pure (Cli_result.error ~command mode err)
                | Ok (Upsert.Upsert_asset {create_action=Some creation;_} as plan) ->
                    let uuid = Option.get (Vec.nth creation.blocks 0).Block.uuid in
                    Upsert.execute plan config >>= fun result ->
                    if Cli_result.is_error result then Cli_effect.pure (Cli_result.with_command command result)
                    else attach uuid
                | Ok _ -> assert false)
           | _ -> Cli_effect.pure (error_result command mode existing))
  | _ -> assert false

let execute action config =
  let (Output.Mode.Packed mode) = Option.value config.Cli_config.output_format ~default:Output_mode.default in
  let command = command_id action.request in
  let request = Server_runtime.ensure_server config action.repo ~create_empty_db:false >>= function
  | Error err -> Cli_effect.pure (Cli_result.error ~command mode err)
  | Ok invoke -> match action.request.kind with
      | List_papers -> execute_list command mode config invoke action
      | Show_paper -> execute_show command mode config invoke action
      | Search_papers -> execute_search command mode config invoke action
      | Import_papers -> execute_import command mode config invoke action
      | Update_paper -> execute_update command mode invoke action
      | Attach_pdf -> execute_attach command mode config invoke action
  in
  Cli_effect.catch request (fun exn -> Cli_effect.pure (error_result command mode (map [|
    kw "reason", kw "request-failed"; kw "detail", string (Printexc.to_string exn);
    kw "paper-uuid", (match action.request.uuid with Some uuid -> string uuid | None -> Edn_util.nil) |])))

let metadata () =
  let make id doc =
    {Command_registry.id;path=Command_id.to_path id;doc;long_doc=Some "Literature JSON fields: title, authors, date, publication, type, doi, arxiv, url, citekey. Import accepts one object or an array; update accepts one patch. Use --input - for stdin and --output json for agents. Omitted fields stay unchanged; null clears optional fields. Local success does not imply remote acknowledgement.";
     examples=Vec.empty;options=Vec.empty;category=Graph_inspect_and_edit;requires_graph=true;requires_auth=false;write_command=Command_id.is_write id;
     human_table_headers_order=(match id with Literature_list | Literature_search -> Vec.of_array [|"uuid";"title";"authors";"date"|] | _ -> Vec.empty)} in
  Vec.of_array [|make Command_id.Literature_list "List literature with bounded pagination";
    make Literature_search "Search the existing literature index";make Literature_show "Show complete bibliography and PDF availability";
    make Literature_import "Import bibliography JSON without overwriting existing papers";make Literature_update "Patch explicitly supplied bibliography fields";
    make Literature_attach "Link an existing PDF or import and link a local PDF"|]
